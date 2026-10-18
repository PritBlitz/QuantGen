package com.quantgen.payment.gateway;

/**
 * The two Razorpay REST calls top-ups need. Implementations throw {@link GatewayException} when Razorpay can't be
 * reached or answers with an error, so callers can map it to 503 GATEWAY_UNAVAILABLE.
 */
public interface RazorpayApi {

    /** An order for {@code amountPaise} INR; returns its id ({@code order_…}). */
    String createOrder(long amountPaise, String receipt);

    /** A payment as Razorpay reports it. */
    Payment fetchPayment(String paymentId);

    /** @param status Razorpay's payment status: created, authorized, captured, refunded or failed */
    record Payment(String id, String orderId, long amountPaise, String currency, String status) {
    }

    class GatewayException extends RuntimeException {
        public GatewayException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
