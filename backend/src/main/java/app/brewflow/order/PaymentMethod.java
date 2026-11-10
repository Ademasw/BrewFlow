package app.brewflow.order;

import app.brewflow.common.ApiException;

/** The tender a person attests to. BrewFlow never processes card data. */
public enum PaymentMethod {
    TRAINING, CASH, EXTERNAL_CARD, EXTERNAL_QR;

    public static PaymentMethod parse(String field, String raw) {
        if (raw != null) {
            for (PaymentMethod m : values()) {
                if (m.name().equals(raw)) {
                    return m;
                }
            }
        }
        throw ApiException.invalid(field, field + " must be one of TRAINING, CASH, EXTERNAL_CARD, EXTERNAL_QR.");
    }
}
