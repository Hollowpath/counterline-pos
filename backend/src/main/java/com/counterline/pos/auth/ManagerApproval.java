package com.counterline.pos.auth;

import com.counterline.pos.staff.Staff;
import com.counterline.pos.staff.StaffRepository;
import com.counterline.pos.staff.StaffRole;
import org.springframework.security.core.AuthenticationException;
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
    private final StaffSessionGuard staffSessions;
    private final ApprovalTokens approvalTokens;

    ManagerApproval(JwtDecoder jwtDecoder, StaffRepository staffRepository, StaffSessionGuard staffSessions,
                    ApprovalTokens approvalTokens) {
        this.jwtDecoder = jwtDecoder;
        this.staffRepository = staffRepository;
        this.staffSessions = staffSessions;
        this.approvalTokens = approvalTokens;
    }

    /**
     * The approving manager: {@code staff} themselves when they are a manager, otherwise the active
     * manager behind {@code approvalToken}, whose approval of a {@link ApprovalOperation#DISCOUNT} is
     * used up. Call this inside the sale's transaction: the approval is spent only if the sale is.
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
        Jwt approval;
        try {
            approval = jwtDecoder.decode(approvalToken);
        } catch (JwtException e) {
            throw new SpecialAccessDeniedException("Approval expired");
        }
        // Only a manager's PIN at a till approves: not, say, a dashboard session's token.
        // A manager's role claim is required here so the session check below cannot be skipped (it
        // passes tokens of other roles by).
        if (TokenService.Channel.of(approval.getClaim(TokenService.CHANNEL_CLAIM)) != TokenService.Channel.APPROVAL
                || !StaffRole.MANAGER.name().equals(approval.getClaimAsString(TokenService.ROLE_CLAIM))
                || ApprovalOperation.of(approval.getClaim(TokenService.OPERATION_CLAIM)) != ApprovalOperation.DISCOUNT) {
            throw new SpecialAccessDeniedException("Manager approval needed");
        }
        // Like a bearer token, it is good only while the manager's session is: a PIN reset, a role or
        // outlet change or a deactivation ends it. Said no more than "needed".
        try {
            staffSessions.check(approval);
        } catch (AuthenticationException e) {
            throw new SpecialAccessDeniedException("Manager approval needed");
        }
        Long managerId;
        try {
            managerId = Long.valueOf(approval.getSubject());
        } catch (NumberFormatException e) {
            throw new SpecialAccessDeniedException("Manager approval needed");
        }
        Staff manager = staffRepository.findByIdAndActiveTrue(managerId)
                .filter(found -> found.getRole() == StaffRole.MANAGER && found.worksAt(staff.getOutletId()))
                .orElseThrow(() -> new SpecialAccessDeniedException("Manager approval needed"));
        approvalTokens.consume(approval, ApprovalOperation.DISCOUNT);
        return manager;
    }
}
