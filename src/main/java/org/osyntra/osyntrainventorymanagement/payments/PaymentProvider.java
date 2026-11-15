package org.osyntra.osyntrainventorymanagement.payments;

/**
 * Local preparation only. Future network adapters must dispatch a committed attempt/outbox outside its transaction.
 */
public interface PaymentProvider {
    enum Outcome {CONFIRMED, DECLINED, UNKNOWN}

    String id();

    Outcome prepare(String requestedOutcome);
}
