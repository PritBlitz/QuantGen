package com.quantgen.support;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import com.quantgen.payment.gateway.RazorpayApi;

/** In-memory Razorpay for tests: orders it created, and payments a test "captures" on them. */
public class FakeRazorpayApi implements RazorpayApi {

    public final Map<String, Long> orders = new ConcurrentHashMap<>();
    public final Map<String, Payment> payments = new ConcurrentHashMap<>();
    public final AtomicBoolean down = new AtomicBoolean(false);

    @Override
    public String createOrder(long amountPaise, String receipt) {
        if (down.get()) {
            throw new GatewayException("down", null);
        }
        String id = "order_" + UUID.randomUUID().toString().replace("-", "").substring(0, 14);
        orders.put(id, amountPaise);
        return id;
    }

    @Override
    public Payment fetchPayment(String paymentId) {
        if (down.get()) {
            throw new GatewayException("down", null);
        }
        Payment p = payments.get(paymentId);
        if (p == null) {
            throw new GatewayException("404", null);
        }
        return p;
    }

    /** Simulates the customer paying an order in Checkout; returns the payment id. */
    public String pay(String orderId, String status) {
        String id = "pay_" + UUID.randomUUID().toString().replace("-", "").substring(0, 14);
        payments.put(id, new Payment(id, orderId, orders.get(orderId), "INR", status));
        return id;
    }
}
