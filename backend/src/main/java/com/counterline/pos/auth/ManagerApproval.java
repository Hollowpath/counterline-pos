package com.counterline.pos.auth;

import com.counterline.pos.staff.Staff;
import com.counterline.pos.staff.StaffRepository;
import com.counterline.pos.staff.StaffRole;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;

/**
 * Checks a manager's approval carried in a request, for an action a cashier may take only with it
 * (a department comp). The approval is the short-lived token a manager gets by entering their PIN at
 * the terminal ("Special Access Required"); the request itself stays the cashier's.
 */
@Component
public class ManagerApproval {

    private final JwtDecoder jwtDecoder;
    private final StaffRepository staffRepository;

    ManagerApproval(JwtDecoder jwtDecoder, StaffRepository staffRepository) {
        this.jwtDecoder = jwtDecoder;
        this.staffRepository = staffRepository;
    }

    /**
     * The approving manager: {@code staff} themselves when they are a manager, otherwise the active
     * manager behind {@code approvalToken}.
     *
     * @throws SpecialAccessDeniedException when there is no valid manager approval
     */
    public Staff requireManager(Staff staff, String approvalToken) {
        if (staff.getRole() == StaffRole.MANAGER) {
            return staff;
        }
        if (approvalToken == null || approvalToken.isBlank()) {
            throw new SpecialAccessDeniedException("Manager approval needed");
        }
        Long managerId;
        try {
            Jwt approval = jwtDecoder.decode(approvalToken);
            // Only a manager's PIN at a till approves: not, say, a dashboard session's token.
            if (TokenService.Channel.of(approval.getClaim(TokenService.CHANNEL_CLAIM)) != TokenService.Channel.APPROVAL) {
                throw new SpecialAccessDeniedException("Manager approval needed");
            }
            managerId = Long.valueOf(approval.getSubject());
        } catch (JwtException | NumberFormatException e) {
            throw new SpecialAccessDeniedException("Approval expired");
        }
        return staffRepository.findByIdAndActiveTrue(managerId)
                .filter(manager -> manager.getRole() == StaffRole.MANAGER && manager.worksAt(staff.getOutletId()))
                .orElseThrow(() -> new SpecialAccessDeniedException("Manager approval needed"));
    }
}
