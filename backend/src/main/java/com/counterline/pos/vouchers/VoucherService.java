package com.counterline.pos.vouchers;

import com.counterline.pos.common.BadRequestException;
import com.counterline.pos.common.BusinessRuleException;
import com.counterline.pos.common.ConflictException;
import com.counterline.pos.common.ResourceNotFoundException;
import com.counterline.pos.discounts.Discount;
import com.counterline.pos.discounts.DiscountAdminService;
import com.counterline.pos.outlet.OutletProperties;
import com.counterline.pos.vouchers.Voucher.Status;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Physical vouchers: head office issues them in batches, each
 * voucher has a printed code, is used once, and expires. At the till the cashier keys in the code;
 * the voucher's value comes off the order as the sale's one discount.
 */
@Service
@Transactional
public class VoucherService {

    private static final Logger log = LoggerFactory.getLogger(VoucherService.class);

    /** No 0/O or 1/I, which are easy to misread on paper. */
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int CODE_LENGTH = 10;

    private final SecureRandom random = new SecureRandom();
    private final VoucherRepository voucherRepository;
    private final VoucherBatchRepository batchRepository;
    private final DiscountAdminService discountAdmin;
    private final OutletProperties outlet;
    private final Clock clock;

    VoucherService(VoucherRepository voucherRepository, VoucherBatchRepository batchRepository,
                   DiscountAdminService discountAdmin, OutletProperties outlet, Clock clock) {
        this.voucherRepository = voucherRepository;
        this.batchRepository = batchRepository;
        this.discountAdmin = discountAdmin;
        this.outlet = outlet;
        this.clock = clock;
    }

    public record BatchRequest(
            @NotBlank @Size(max = 50) String name,
            @NotNull @DecimalMin("0.01") @Digits(integer = 6, fraction = 2) BigDecimal value,
            @NotNull LocalDate expiresOn,
            @Min(1) @Max(500) int quantity) {
    }

    public record BatchSummary(Long id, String name, BigDecimal value, LocalDate expiresOn, long issued, long used,
                               long cancelled, String createdBy, Instant createdAt) {
    }

    /** The new batch and its codes, to print. */
    public record CreatedBatch(BatchSummary batch, List<String> codes) {
    }

    public record VoucherRow(String code, Status status, Instant usedAt) {
    }

    /** What the till shows when a code is keyed in. */
    public record VoucherView(String code, String name, BigDecimal value, LocalDate expiresOn, Status status) {
    }

    // ---- Head office ----

    public CreatedBatch createBatch(BatchRequest request, String createdBy) {
        if (request.expiresOn().isBefore(today())) {
            throw new BadRequestException("The expiry date has passed");
        }
        String name = request.name().strip();
        Discount discount;
        try {
            discount = discountAdmin.createVoucherDiscount(name + " (expires " + request.expiresOn() + ")", request.value());
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("There is already a voucher batch called " + name + " expiring then");
        }
        VoucherBatch batch = batchRepository.save(new VoucherBatch(name, discount, request.expiresOn(), createdBy, clock.instant()));
        Set<String> codes = newCodes(request.quantity());
        voucherRepository.saveAll(codes.stream().map(code -> new Voucher(batch, code)).toList());
        log.info("Voucher batch {} ({}) of {} issued by {}", batch.getId(), name, codes.size(), createdBy);
        return new CreatedBatch(summary(batch, codes.size(), 0, 0), List.copyOf(codes));
    }

    @Transactional(readOnly = true)
    public List<BatchSummary> batches() {
        Map<Long, VoucherRepository.BatchCounts> counts = voucherRepository.countByBatch().stream()
                .collect(Collectors.toMap(VoucherRepository.BatchCounts::getBatchId, Function.identity()));
        return batchRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(batch -> {
                    VoucherRepository.BatchCounts c = counts.get(batch.getId());
                    return c == null ? summary(batch, 0, 0, 0) : summary(batch, c.getIssued(), c.getUsed(), c.getCancelled());
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public List<VoucherRow> vouchers(Long batchId) {
        batchRepository.findById(batchId).orElseThrow(() -> new ResourceNotFoundException("Voucher batch " + batchId + " not found"));
        LocalDate today = today();
        return voucherRepository.findByBatchIdOrderByCode(batchId).stream()
                .map(voucher -> new VoucherRow(voucher.getCode(), voucher.statusOn(today), voucher.getUsedAt()))
                .toList();
    }

    // ---- The till ----

    /** The voucher with this code, however it was typed; 404 if there is none. */
    @Transactional(readOnly = true)
    public VoucherView check(String code) {
        Voucher voucher = voucherRepository.findByCode(normalize(code))
                .orElseThrow(() -> new ResourceNotFoundException("Voucher not found"));
        VoucherBatch batch = voucher.getBatch();
        return new VoucherView(voucher.getCode(), batch.getName(), batch.getDiscount().getValue(), batch.getExpiresOn(),
                voucher.statusOn(today()));
    }

    /**
     * A voucher to use on a sale. {@code lock}: hold it until the sale's transaction ends (at
     * checkout); a quote only checks it.
     *
     * @throws BusinessRuleException if it is unknown, used, expired or cancelled
     */
    public Voucher forSale(String code, boolean lock) {
        String normalized = normalize(code);
        Voucher voucher = (lock ? voucherRepository.lockByCode(normalized) : voucherRepository.findByCode(normalized))
                .orElseThrow(() -> new BusinessRuleException(unknown(code)));
        problemWith(voucher).ifPresent(problem -> {
            throw new BusinessRuleException(problem);
        });
        return voucher;
    }

    /** For a quote: the voucher's discount, or why it can't be used ({@code discount} null). */
    public record QuoteCheck(Discount discount, String problem) {
    }

    /**
     * Like {@link #forSale} but never throws, so the quote's own transaction carries on.
     * {@code editedOrderId}: a placed order being edited, whose own voucher is still fine for it (or 0).
     */
    @Transactional(readOnly = true)
    public QuoteCheck forQuote(String code, long editedOrderId) {
        return voucherRepository.findByCode(normalize(code))
                .map(voucher -> (Long.valueOf(editedOrderId).equals(voucher.getUsedOrderId())
                                ? expiryProblem(voucher) : problemWith(voucher))
                        .map(problem -> new QuoteCheck(null, problem))
                        .orElseGet(() -> new QuoteCheck(voucher.getBatch().getDiscount(), null)))
                .orElseGet(() -> new QuoteCheck(null, unknown(code)));
    }

    private Optional<String> problemWith(Voucher voucher) {
        return switch (voucher.statusOn(today())) {
            case USED -> Optional.of("Voucher used");
            case EXPIRED -> Optional.of("Voucher expired");
            case CANCELLED -> Optional.of("Voucher cancelled");
            case UNUSED -> Optional.empty();
        };
    }

    private Optional<String> expiryProblem(Voucher voucher) {
        return today().isAfter(voucher.getBatch().getExpiresOn())
                ? Optional.of("Voucher expired")
                : Optional.empty();
    }

    private static String unknown(String code) {
        return "Voucher not found: " + code.strip();
    }

    /** What the voucher takes off the sale. */
    public Discount discountOf(Voucher voucher) {
        return voucher.getBatch().getDiscount();
    }

    public void markUsed(Voucher voucher, Long orderId) {
        voucher.use(orderId, clock.instant());
        voucherRepository.save(voucher);
    }

    /**
     * Paying a placed order: the voucher it was placed with must still be valid today (it may have
     * expired since the order was placed).
     */
    public void requireStillValidFor(Long orderId) {
        for (Voucher voucher : voucherRepository.findByUsedOrderId(orderId)) {
            expiryProblem(voucher).ifPresent(problem -> {
                throw new BusinessRuleException(problem);
            });
        }
    }

    /** The code of the voucher a (placed) order uses, if any. */
    public Optional<String> codeUsedBy(Long orderId) {
        return voucherRepository.findByUsedOrderId(orderId).stream().map(Voucher::getCode).findFirst();
    }

    /**
     * A voided or refunded sale (or one being edited) gives its voucher back: the code can be used
     * again, until it expires. On a refund the staff hand the paper voucher back with the money.
     */
    public void releaseFor(Long orderId) {
        for (Voucher voucher : voucherRepository.findByUsedOrderId(orderId)) {
            voucher.release();
            log.info("Voucher {} released from order {}", voucher.getCode(), orderId);
        }
    }

    private Set<String> newCodes(int quantity) {
        Set<String> codes = new LinkedHashSet<>();
        while (codes.size() < quantity) {
            Set<String> candidates = new HashSet<>();
            while (candidates.size() < quantity - codes.size()) {
                StringBuilder code = new StringBuilder(CODE_LENGTH);
                for (int i = 0; i < CODE_LENGTH; i++) {
                    code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
                }
                candidates.add(code.toString());
            }
            candidates.removeAll(voucherRepository.findExistingCodes(candidates));
            codes.addAll(candidates);
        }
        return codes;
    }

    private static String normalize(String code) {
        return code.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(outlet.timeZone()));
    }

    private static BatchSummary summary(VoucherBatch batch, long issued, long used, long cancelled) {
        return new BatchSummary(batch.getId(), batch.getName(), batch.getDiscount().getValue(), batch.getExpiresOn(),
                issued, used, cancelled, batch.getCreatedBy(), batch.getCreatedAt());
    }
}
