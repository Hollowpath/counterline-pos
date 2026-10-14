package com.counterline.pos.orders;

import com.counterline.pos.common.BusinessRuleException;
import com.counterline.pos.orders.OrderDtos.PaymentRequest;
import com.counterline.pos.staff.Staff;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Adds requested payments to an order, accepting only active payment methods. */
@Component
class PaymentRecorder {

    private final PaymentMethodRepository paymentMethodRepository;

    PaymentRecorder(PaymentMethodRepository paymentMethodRepository) {
        this.paymentMethodRepository = paymentMethodRepository;
    }

    void addPayments(SaleOrder order, List<PaymentRequest> payments, Staff takenBy, Instant at) {
        Map<Long, PaymentMethod> methods = paymentMethodRepository.findAllById(
                        payments.stream().map(PaymentRequest::paymentMethodId).collect(Collectors.toSet()))
                .stream()
                .filter(PaymentMethod::isActive)
                .collect(Collectors.toMap(PaymentMethod::getId, Function.identity()));

        for (PaymentRequest payment : payments) {
            PaymentMethod method = methods.get(payment.paymentMethodId());
            if (method == null) {
                throw new BusinessRuleException("Payment method " + payment.paymentMethodId() + " is not available");
            }
            String reference = payment.reference() == null || payment.reference().isBlank()
                    ? null : payment.reference().strip();
            order.addPayment(method, payment.amount(), reference, payment.cashTendered(), takenBy, at);
        }
    }
}
