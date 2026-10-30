package com.counterline.pos.discounts;

import com.counterline.pos.common.BadRequestException;
import com.counterline.pos.common.ConflictException;
import com.counterline.pos.common.ResourceNotFoundException;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/** Head office edits the discounts offered at every till; past sales keep the terms they were given with. */
@Service
@Transactional
public class DiscountAdminService {

    private static final Logger log = LoggerFactory.getLogger(DiscountAdminService.class);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final DiscountRepository discountRepository;

    DiscountAdminService(DiscountRepository discountRepository) {
        this.discountRepository = discountRepository;
    }

    public record AdminDiscount(Long id, String name, Discount.Kind kind, BigDecimal value, Discount.Scope scope,
                                boolean requiresApproval, boolean notForMembers, boolean countsAsVoucher, boolean active) {

        static AdminDiscount from(Discount d) {
            return new AdminDiscount(d.getId(), d.getName(), d.getKind(), d.getValue(), d.getScope(),
                    d.isRequiresApproval(), d.isNotForMembers(), d.isCountsAsVoucher(), d.isActive());
        }
    }

    public record DiscountRequest(
            @NotBlank @Size(max = 80) String name,
            @NotNull Discount.Kind kind,
            @NotNull @DecimalMin(value = "0.01") @Digits(integer = 8, fraction = 2) BigDecimal value,
            @NotNull Discount.Scope scope,
            Boolean requiresApproval,
            /* Can't be given on a member's sale (the staff discount). */
            Boolean notForMembers,
            /* A paper voucher keyed in at the till: its sale gives no free items but the bags and Member FOC. */
            Boolean countsAsVoucher,
            Boolean active) {
    }

    @Transactional(readOnly = true)
    public List<AdminDiscount> discounts() {
        // Voucher batches' discounts are managed with their vouchers.
        return discountRepository.findAllByOrderBySortOrderAsc().stream()
                .filter(discount -> discount.getScope() != Discount.Scope.VOUCHER)
                .map(AdminDiscount::from)
                .toList();
    }

    public AdminDiscount create(DiscountRequest request) {
        String name = validated(request);
        if (discountRepository.existsByNameIgnoreCase(name)) {
            throw new ConflictException("There is already a discount called " + name);
        }
        Discount discount = new Discount(name, request.kind(), request.value(), request.scope());
        discount.edit(name, request.kind(), request.value(), request.scope(), Boolean.TRUE.equals(request.requiresApproval()),
                Boolean.TRUE.equals(request.notForMembers()), Boolean.TRUE.equals(request.countsAsVoucher()), true);
        discount.placeAt((short) (discountRepository.maxSortOrder() + 1));
        discount = discountRepository.saveAndFlush(discount);
        log.info("Discount {} ({}) added", discount.getId(), name);
        return AdminDiscount.from(discount);
    }

    public AdminDiscount update(Long discountId, DiscountRequest request) {
        Discount discount = discountRepository.findById(discountId)
                .orElseThrow(() -> new ResourceNotFoundException("Discount " + discountId + " not found"));
        if (discount.getScope() == Discount.Scope.VOUCHER) {
            throw new ConflictException("A voucher batch's discount can't be edited; it is managed with its vouchers");
        }
        String name = validated(request);
        if (discountRepository.existsByNameIgnoreCaseAndIdNot(name, discountId)) {
            throw new ConflictException("There is already a discount called " + name);
        }
        discount.edit(name, request.kind(), request.value(), request.scope(), Boolean.TRUE.equals(request.requiresApproval()),
                Boolean.TRUE.equals(request.notForMembers()), Boolean.TRUE.equals(request.countsAsVoucher()),
                request.active() == null || request.active());
        log.info("Discount {} edited: {} {} {}", discountId, name, request.kind(), request.value());
        return AdminDiscount.from(discount);
    }

    /** The discount behind a new voucher batch: RM {@code value} off the order. */
    public Discount createVoucherDiscount(String name, BigDecimal value) {
        Discount discount = new Discount(name, Discount.Kind.AMOUNT, value, Discount.Scope.VOUCHER);
        discount.placeAt((short) (discountRepository.maxSortOrder() + 1));
        return discountRepository.saveAndFlush(discount);
    }

    private static String validated(DiscountRequest request) {
        if (request.scope() == Discount.Scope.VOUCHER) {
            throw new BadRequestException("Voucher discounts are created with their vouchers");
        }
        if (request.kind() == Discount.Kind.PERCENT && request.value().compareTo(HUNDRED) > 0) {
            throw new BadRequestException("A percentage discount can be at most 100%");
        }
        return request.name().strip();
    }
}
