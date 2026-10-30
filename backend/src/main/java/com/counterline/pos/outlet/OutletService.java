package com.counterline.pos.outlet;

import com.counterline.pos.common.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

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

    /** @throws ResourceNotFoundException for an unknown or closed outlet */
    public OutletResponse get(Long id) {
        return OutletResponse.from(getActive(id));
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

    /** A reference without loading the row; the caller already knows the outlet exists (it is in the token). */
    public Outlet reference(Long id) {
        return outletRepository.getReferenceById(id);
    }
}
