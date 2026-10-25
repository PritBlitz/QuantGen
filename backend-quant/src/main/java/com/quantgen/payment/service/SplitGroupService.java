package com.quantgen.payment.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.quantgen.auth.api.AuthDtos.UserDto;
import com.quantgen.auth.service.UserService;
import com.quantgen.common.error.ApiException;
import com.quantgen.common.error.ErrorCode;
import com.quantgen.common.money.Money;
import com.quantgen.payment.service.PaymentRequestService.PaymentRequestDto;

/**
 * F-PAY-12 split groups. Members are handles; shares are computed here (never by the client), and the leftover
 * paisa of a split goes to the payer, so the shares of an expense always add up to its amount exactly. Nothing
 * moves money: "settle up" asks each debtor for what they owe through payment requests, and a paid settle-up
 * request counts as a settlement in the group.
 *
 * <p>A member's balance = paid − shares + settle-ups they paid − settle-ups paid to them. Positive: the group owes
 * them. The balances of a group always sum to zero.
 */
@Service
public class SplitGroupService {

    public static final int MAX_MEMBERS = 20;
    public static final int MAX_EXPENSES = 500;
    static final BigDecimal HUNDRED = new BigDecimal("100");
    static final BigDecimal MIN_REQUEST = new BigDecimal("1.00");

    public enum SplitType { EQUAL, EXACT, PERCENT }

    public record MemberDto(String handle, String displayName, BigDecimal balance, boolean left) {
    }

    public record ShareDto(String handle, BigDecimal amount) {
    }

    public record ExpenseDto(UUID id, String paidByHandle, BigDecimal amount, String description, SplitType splitType,
                             List<ShareDto> shares, Instant createdAt) {
    }

    public record GroupDto(UUID id, String name, List<MemberDto> members, List<ExpenseDto> expenses,
                           Instant createdAt) {
    }

    /** {@code value}: rupees for EXACT, percent for PERCENT. */
    public record ShareInput(String handle, BigDecimal value) {
    }

    private record Member(UUID userId, String handle, boolean left) {
    }

    private final JdbcTemplate jdbc;
    private final HandleService handles;
    private final UserService users;
    private final PaymentRequestService requests;

    public SplitGroupService(JdbcTemplate jdbc, HandleService handles, UserService users,
                             PaymentRequestService requests) {
        this.jdbc = jdbc;
        this.handles = handles;
        this.users = users;
        this.requests = requests;
    }

    @Transactional
    public GroupDto create(UUID userId, String rawName, List<String> memberHandles) {
        if (handles.handleOf(userId) == null) {
            throw new ApiException(ErrorCode.INVALID_STATE, "Set your handle before creating a group");
        }
        String name = rawName == null ? "" : rawName.trim();
        if (name.isEmpty() || name.length() > 40) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "name", "must be 1-40 characters");
        }
        List<UUID> memberIds = new ArrayList<>(List.of(userId));
        for (String h : memberHandles) {
            UUID id = handles.userFor(h).orElseThrow(() -> ApiException.fieldError(HttpStatus.BAD_REQUEST,
                    "memberHandles", h + " is not a QuantGen handle"));
            if (!memberIds.contains(id)) {
                memberIds.add(id);
            }
        }
        if (memberIds.size() < 2) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "memberHandles", "add at least one other person");
        }
        if (memberIds.size() > MAX_MEMBERS) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "memberHandles",
                    "a group has at most " + MAX_MEMBERS + " members");
        }
        UUID groupId = jdbc.queryForObject("INSERT INTO split_groups (name, created_by) VALUES (?, ?) RETURNING id",
                UUID.class, name, userId);
        for (int i = 0; i < memberIds.size(); i++) {
            jdbc.update("INSERT INTO split_members (group_id, user_id, position) VALUES (?, ?, ?)", groupId,
                    memberIds.get(i), i);
        }
        return view(groupId);
    }

    @Transactional(readOnly = true)
    public List<GroupDto> list(UUID userId) {
        return jdbc.queryForList("""
                SELECT g.id FROM split_groups g JOIN split_members m ON m.group_id = g.id AND m.user_id = ?
                 WHERE m.left_at IS NULL ORDER BY g.created_at DESC, g.id DESC
                """, UUID.class, userId).stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public GroupDto get(UUID userId, UUID groupId) {
        requireMember(userId, groupId);
        return view(groupId);
    }

    @Transactional
    public ExpenseDto addExpense(UUID userId, UUID groupId, String paidByHandle, BigDecimal rawAmount,
                                 String rawDescription, SplitType type, List<ShareInput> shareInputs) {
        requireMember(userId, groupId);
        // Serialize writes to one group (expense cap, consistent reads for settle-up).
        jdbc.queryForList("SELECT id FROM split_groups WHERE id = ? FOR UPDATE", UUID.class, groupId);
        List<Member> members = members(groupId).stream().filter(m -> !m.left()).toList();
        Map<String, UUID> byHandle = new HashMap<>();
        members.forEach(m -> byHandle.put(m.handle(), m.userId()));
        UUID payer = byHandle.get(HandleService.normalize(paidByHandle));
        if (payer == null) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "paidByHandle", "must be a group member");
        }
        String description = rawDescription == null ? "" : rawDescription.trim();
        if (description.isEmpty() || description.length() > 60) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "description", "must be 1-60 characters");
        }
        Integer count = jdbc.queryForObject("SELECT count(*) FROM split_expenses WHERE group_id = ?", Integer.class,
                groupId);
        if (count != null && count >= MAX_EXPENSES) {
            throw new ApiException(ErrorCode.LIMIT_EXCEEDED, "A group has at most " + MAX_EXPENSES + " expenses");
        }
        BigDecimal amount = Money.normalize(rawAmount).setScale(2, RoundingMode.UNNECESSARY);
        Map<UUID, BigDecimal> shares = computeShares(type, amount, payer, members, byHandle,
                shareInputs == null ? List.of() : shareInputs);

        UUID expenseId = jdbc.queryForObject("""
                INSERT INTO split_expenses (group_id, paid_by_user_id, amount, description, split_type, created_by)
                VALUES (?, ?, ?, ?, ?, ?) RETURNING id
                """, UUID.class, groupId, payer, amount, description, type.name(), userId);
        shares.forEach((member, share) -> jdbc.update(
                "INSERT INTO split_shares (expense_id, user_id, amount) VALUES (?, ?, ?)", expenseId, member, share));
        return expenses(groupId, handlesOf(members)).stream().filter(e -> e.id().equals(expenseId)).findFirst()
                .orElseThrow();
    }

    /**
     * The caller asks each member who owes the group for what they owe, up to what the group owes the caller, net of
     * settle-up requests that are already open. Returns the requests created (possibly none).
     */
    @Transactional
    public List<PaymentRequestDto> settle(UUID userId, UUID groupId) {
        requireMember(userId, groupId);
        jdbc.queryForList("SELECT id FROM split_groups WHERE id = ? FOR UPDATE", UUID.class, groupId);
        List<Member> members = members(groupId);
        Map<UUID, BigDecimal> balances = balances(groupId, members);
        String groupName = jdbc.queryForObject("SELECT name FROM split_groups WHERE id = ?", String.class, groupId);

        Map<UUID, BigDecimal> openToPayer = new HashMap<>();
        BigDecimal[] openFromMe = {BigDecimal.ZERO};
        jdbc.query("""
                SELECT requester_user_id, payer_user_id, amount FROM payment_requests
                 WHERE split_group_id = ? AND status = 'OPEN' AND expires_at > now()
                """, rs -> {
            UUID requester = rs.getObject("requester_user_id", UUID.class);
            UUID payerId = rs.getObject("payer_user_id", UUID.class);
            BigDecimal amt = rs.getBigDecimal("amount");
            openToPayer.merge(payerId, amt, BigDecimal::add);
            if (requester.equals(userId)) {
                openFromMe[0] = openFromMe[0].add(amt);
            }
        }, groupId);

        BigDecimal credit = balances.get(userId).subtract(openFromMe[0]);
        List<PaymentRequestDto> created = new ArrayList<>();
        for (Member m : members) {
            if (m.userId().equals(userId) || credit.compareTo(MIN_REQUEST) < 0) {
                continue;
            }
            BigDecimal owes = balances.get(m.userId()).negate()
                    .subtract(openToPayer.getOrDefault(m.userId(), BigDecimal.ZERO));
            BigDecimal amount = owes.min(credit);
            if (amount.compareTo(MIN_REQUEST) < 0 || blocked(m.userId(), userId)) {
                continue;
            }
            created.add(requests.createInternal(userId, m.handle(), amount, "Settle up · " + groupName, groupId));
            credit = credit.subtract(amount);
        }
        return created;
    }

    // ------------------------------------------------------------------ split maths

    static Map<UUID, BigDecimal> computeShares(SplitType type, BigDecimal amount, UUID payer, List<Member> members,
                                               Map<String, UUID> byHandle, List<ShareInput> inputs) {
        Map<UUID, BigDecimal> shares = new LinkedHashMap<>();
        switch (type) {
            case EQUAL -> {
                BigDecimal each = amount.divide(BigDecimal.valueOf(members.size()), 2, RoundingMode.DOWN);
                members.forEach(m -> shares.put(m.userId(), each));
            }
            case EXACT, PERCENT -> {
                if (inputs.isEmpty()) {
                    throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "split.shares", "required for " + type);
                }
                Set<UUID> seen = new HashSet<>();
                BigDecimal total = BigDecimal.ZERO;
                for (ShareInput in : inputs) {
                    UUID member = in.handle() == null ? null : byHandle.get(HandleService.normalize(in.handle()));
                    if (member == null || !seen.add(member)) {
                        throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "split.shares",
                                "each share needs a different group member");
                    }
                    BigDecimal v = in.value();
                    if (v == null || v.signum() < 0 || v.stripTrailingZeros().scale() > 2
                            || (type == SplitType.PERCENT && v.compareTo(HUNDRED) > 0)) {
                        throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "split.shares",
                                type == SplitType.EXACT ? "amounts must be 0 or more with at most 2 decimals"
                                        : "percentages must be 0-100 with at most 2 decimals");
                    }
                    total = total.add(v);
                    shares.put(member, type == SplitType.EXACT ? v.setScale(2, RoundingMode.UNNECESSARY)
                            : amount.multiply(v).divide(HUNDRED, 2, RoundingMode.DOWN));
                }
                if (type == SplitType.EXACT && total.compareTo(amount) != 0) {
                    throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "split.shares",
                            "shares must add up to the amount");
                }
                if (type == SplitType.PERCENT && total.compareTo(HUNDRED) != 0) {
                    throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "split.shares",
                            "percentages must add up to 100");
                }
            }
        }
        // Leftover paisa (from rounding down) goes to the payer, so shares add up to the amount exactly.
        BigDecimal allocated = shares.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal leftover = amount.subtract(allocated);
        if (leftover.signum() != 0) {
            shares.merge(payer, leftover, BigDecimal::add);
        }
        return shares;
    }

    /**
     * Leaves a group once you're settled: your balance in it is zero and no settle-up request of the group is open
     * for or from you. You stay in its history (expenses and shares); you just can't be added to new expenses.
     */
    @Transactional
    public void leave(UUID userId, UUID groupId) {
        requireMember(userId, groupId);
        jdbc.queryForList("SELECT id FROM split_groups WHERE id = ? FOR UPDATE", UUID.class, groupId);
        BigDecimal balance = balances(groupId, members(groupId)).getOrDefault(userId, BigDecimal.ZERO);
        if (balance.signum() != 0) {
            throw new ApiException(ErrorCode.INVALID_STATE, balance.signum() > 0
                    ? "The group still owes you " + Money.toJson(balance) + "; settle up first"
                    : "You still owe the group " + Money.toJson(balance.negate()) + "; settle up first");
        }
        Boolean open = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM payment_requests WHERE split_group_id = ? AND status = 'OPEN'
                                 AND expires_at > now() AND (payer_user_id = ? OR requester_user_id = ?))
                """, Boolean.class, groupId, userId, userId);
        if (Boolean.TRUE.equals(open)) {
            throw new ApiException(ErrorCode.INVALID_STATE, "A settle-up request of this group is still open for you");
        }
        jdbc.update("UPDATE split_members SET left_at = now() WHERE group_id = ? AND user_id = ?", groupId, userId);
    }

    // ------------------------------------------------------------------ reads

    private GroupDto view(UUID groupId) {
        List<Member> members = members(groupId);
        Map<UUID, BigDecimal> balances = balances(groupId, members);
        Map<UUID, UserDto> people = users.findUsers(members.stream().map(Member::userId).toList());
        List<MemberDto> memberDtos = members.stream().map(m -> new MemberDto(m.handle(),
                HandleService.maskName(people.containsKey(m.userId()) ? people.get(m.userId()).fullName() : ""),
                Money.normalize(balances.get(m.userId())), m.left())).toList();
        return jdbc.queryForObject("SELECT name, created_at FROM split_groups WHERE id = ?",
                (rs, i) -> new GroupDto(groupId, rs.getString("name"), memberDtos,
                        expenses(groupId, handlesOf(members)), rs.getTimestamp("created_at").toInstant()), groupId);
    }

    private List<ExpenseDto> expenses(UUID groupId, Map<UUID, String> handleOf) {
        Map<UUID, List<ShareDto>> shares = new HashMap<>();
        jdbc.query("""
                SELECT s.expense_id, s.user_id, s.amount FROM split_shares s
                  JOIN split_expenses e ON e.id = s.expense_id
                  JOIN split_members m ON m.group_id = e.group_id AND m.user_id = s.user_id
                 WHERE e.group_id = ? ORDER BY m.position
                """, rs -> {
            shares.computeIfAbsent(rs.getObject("expense_id", UUID.class), k -> new ArrayList<>())
                    .add(new ShareDto(handleOf.get(rs.getObject("user_id", UUID.class)), rs.getBigDecimal("amount")));
        }, groupId);
        return jdbc.query("SELECT id, paid_by_user_id, amount, description, split_type, created_at FROM split_expenses "
                        + "WHERE group_id = ? ORDER BY created_at DESC, id DESC",
                (rs, i) -> {
                    UUID id = rs.getObject("id", UUID.class);
                    return new ExpenseDto(id, handleOf.get(rs.getObject("paid_by_user_id", UUID.class)),
                            rs.getBigDecimal("amount"), rs.getString("description"),
                            SplitType.valueOf(rs.getString("split_type")), shares.getOrDefault(id, List.of()),
                            rs.getTimestamp("created_at").toInstant());
                }, groupId);
    }

    private Map<UUID, BigDecimal> balances(UUID groupId, List<Member> members) {
        Map<UUID, BigDecimal> balances = new HashMap<>();
        members.forEach(m -> balances.put(m.userId(), BigDecimal.ZERO));
        jdbc.query("SELECT paid_by_user_id, SUM(amount) AS v FROM split_expenses WHERE group_id = ? "
                + "GROUP BY paid_by_user_id", rs -> {
            balances.merge(rs.getObject("paid_by_user_id", UUID.class), rs.getBigDecimal("v"), BigDecimal::add);
        }, groupId);
        jdbc.query("SELECT s.user_id, SUM(s.amount) AS v FROM split_shares s JOIN split_expenses e "
                + "ON e.id = s.expense_id WHERE e.group_id = ? GROUP BY s.user_id", rs -> {
            balances.merge(rs.getObject("user_id", UUID.class), rs.getBigDecimal("v").negate(), BigDecimal::add);
        }, groupId);
        jdbc.query("SELECT requester_user_id, payer_user_id, amount FROM payment_requests "
                + "WHERE split_group_id = ? AND status = 'PAID'", rs -> {
            BigDecimal amt = rs.getBigDecimal("amount");
            balances.merge(rs.getObject("payer_user_id", UUID.class), amt, BigDecimal::add);
            balances.merge(rs.getObject("requester_user_id", UUID.class), amt.negate(), BigDecimal::add);
        }, groupId);
        return balances;
    }

    private List<Member> members(UUID groupId) {
        return jdbc.query("""
                SELECT m.user_id, h.handle, m.left_at FROM split_members m LEFT JOIN handles h ON h.user_id = m.user_id
                 WHERE m.group_id = ? ORDER BY m.position
                """, (rs, i) -> new Member(rs.getObject("user_id", UUID.class), rs.getString("handle"),
                rs.getTimestamp("left_at") != null), groupId);
    }

    private static Map<UUID, String> handlesOf(List<Member> members) {
        Map<UUID, String> out = new HashMap<>();
        members.forEach(m -> out.put(m.userId(), m.handle()));
        return out;
    }

    private void requireMember(UUID userId, UUID groupId) {
        Boolean member = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM split_members WHERE group_id = ? "
                + "AND user_id = ? AND left_at IS NULL)", Boolean.class, groupId, userId);
        if (!Boolean.TRUE.equals(member)) {
            throw ApiException.notFound("Group");
        }
    }

    private boolean blocked(UUID payer, UUID requester) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM blocked_requesters "
                + "WHERE user_id = ? AND blocked_user_id = ?)", Boolean.class, payer, requester));
    }
}
