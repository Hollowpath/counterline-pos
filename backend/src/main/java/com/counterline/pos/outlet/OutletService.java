package com.counterline.pos.outlet;

import com.counterline.pos.common.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@Transactional(readOnly = true)
public class OutletService {

    private final OutletRepository outletRepository;

    OutletService(OutletRepository outletRepository) {
        this.outletRepository = outletRepository;
    }

    public record OutletResponse(Long id, String code, String name, String address) {

        static OutletResponse from(Outlet outlet) {
            return new OutletResponse(outlet.getId(), outlet.getCode(), outlet.getName(), outlet.getAddress());
        }
    }

    public List<OutletResponse> activeOutlets() {
        return outletRepository.findByActiveTrueOrderByName().stream().map(OutletResponse::from).toList();
    }

    /** An outlet as the back office sees it in history: {@code closed} when it has been deactivated (its sales remain). */
    public record HistoricOutlet(Long id, String code, String name, String address, boolean closed) {

        static HistoricOutlet from(Outlet outlet) {
            return new HistoricOutlet(outlet.getId(), outlet.getCode(), outlet.getName(), outlet.getAddress(), !outlet.isActive());
        }
    }

    /** Every outlet by name, closed ones too: for reports and charts over a period, which keep their history. */
    public List<HistoricOutlet> allOutlets() {
        return outletRepository.findAllByOrderByCodeAsc().stream().map(HistoricOutlet::from)
                .sorted(java.util.Comparator.comparing(HistoricOutlet::name, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(HistoricOutlet::id))
                .toList();
    }

    /** @throws ResourceNotFoundException for an unknown outlet (a closed one is found: its history is still there) */
    public HistoricOutlet findAny(Long id) {
        return outletRepository.findById(id).map(HistoricOutlet::from)
                .orElseThrow(() -> new ResourceNotFoundException("Outlet " + id + " not found"));
    }

    /** @throws ResourceNotFoundException for an unknown or closed outlet */
    public OutletResponse get(Long id) {
        return OutletResponse.from(getActive(id));
    }

    /**
     * The open outlet, or empty for an unknown or closed one. Unlike {@link #get} it throws nothing, so a caller inside
     * a transaction can carry on (an exception through a transactional service marks the whole transaction rollback-only).
     */
    public Optional<OutletResponse> findOpen(Long id) {
        return outletRepository.findByIdAndActiveTrue(id).map(OutletResponse::from);
    }

    /** For recording against an outlet (a sale, a register session). */
    public Outlet getActive(Long id) {
        return outletRepository.findByIdAndActiveTrue(id)
                .orElseThrow(() -> new ResourceNotFoundException("Outlet " + id + " not found"));
    }

    /**
     * For starting a shift: the outlet must be open, and stays open (it cannot be deactivated) until this
     * transaction ends. Locking order everywhere: the outlet first, then the staff member.
     *
     * @throws ResourceNotFoundException for an unknown or closed outlet
     */
    public Outlet getActiveHeldOpen(Long id) {
        return outletRepository.lockShared(id).filter(Outlet::isActive)
                .orElseThrow(() -> new ResourceNotFoundException("Outlet " + id + " not found"));
    }

    /**
     * For a change that must not run beside another change to the outlet's staff or state (deactivating the
     * outlet, removing its last manager): the row is locked exclusively until this transaction ends, whether the
     * outlet is open or not. Locking order everywhere: the outlets (lowest id first), then the staff member.
     *
     * @throws ResourceNotFoundException for an unknown outlet
     */
    public Outlet lockForChange(Long id) {
        return outletRepository.lockForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("Outlet " + id + " not found"));
    }

    /** A reference without loading the row; the caller already knows the outlet exists (it is in the token). */
    public Outlet reference(Long id) {
        return outletRepository.getReferenceById(id);
    }
}
