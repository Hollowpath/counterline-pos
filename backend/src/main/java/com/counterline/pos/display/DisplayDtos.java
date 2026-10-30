package com.counterline.pos.display;

import com.counterline.pos.customers.Gender;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class DisplayDtos {

    private DisplayDtos() {
    }

    // ---- The POS's view of its display ----

    /**
     * {@code accessCode} is shown until a display connects with it. {@code waitingMember}: someone
     * who logged in or signed up on the display, for the till to add to the sale.
     */
    public record DisplayStatus(Long id, String accessCode, boolean connected, Instant displaySeenAt,
                                WaitingMember waitingMember) {
    }

    public record WaitingMember(Long customerId, String fullName, String phone, boolean newMember) {
    }

    /** The till added the member waiting on the display to its sale. */
    public record ConfirmRequest(@NotNull Long customerId) {
    }

    // ---- What the customer sees ----

    public enum Stage {
        /** No sale open on the till: the display shows its posters. */
        IDLE,
        /** A sale is open (New Sale, or items in it): its lines, and member login / sign-up. */
        CART,
        /** Paid: "Thank you". */
        THANK_YOU
    }

    /**
     * The POS's cart as the customer sees it. Published by the POS from its own (server-priced)
     * figures; the display only shows it. Points are never shown: the screen faces the shop.
     */
    public record CartView(
            @NotNull Stage stage,
            @NotNull @Size(max = 100) List<@Valid @NotNull CartLineView> lines,
            @Min(0) int itemCount,
            @NotNull BigDecimal subtotal,
            @NotNull BigDecimal discount,
            @NotNull BigDecimal total,
            /* The member on the sale (first name only on the display), if any. */
            @Size(max = 60) String memberName) {
    }

    /**
     * {@code price}: before discounts and free items; {@code total}: what is charged. {@code free}: a
     * promotional or sample item (charged nothing).
     */
    public record CartLineView(@Min(1) int quantity, @NotBlank @Size(max = 120) String name,
                               @Size(max = 200) String detail, @NotNull BigDecimal price, @NotNull BigDecimal total,
                               boolean free) {
    }

    public enum MemberStatus {
        NONE,
        /** Logged in or signed up; the till hasn't added them to the sale yet. */
        WAITING,
        /** On the sale. */
        CONFIRMED
    }

    /** {@code firstName} only, never the phone or full name: the screen faces the shop. */
    public record MemberState(MemberStatus status, String firstName) {
    }

    /** {@code cart} is null until the POS publishes one. */
    public record DisplayState(long version, String outletName, CartView cart, MemberState member) {
    }

    /**
     * The promotional posters the display may show: a closed set of keys, whose wording the display
     * owns. {@code validUntil}: after this the answer must not be shown (the display asks again).
     */
    public enum Poster {
        BUY_THREE, SEASONAL, MEMBER_POINTS, BIRTHDAY, SPEND_MEMBERS, SPEND_ANYONE
    }

    public record DisplayPosters(List<Poster> posters, Instant validUntil) {
    }

    // ---- Pairing and the customer's actions ----

    public record ConnectRequest(@NotNull @Pattern(regexp = "\\d{6}", message = "must be 6 digits") String accessCode) {
    }

    public record DisplayConnection(String token, Instant expiresAt, Long displayId, String outletName) {
    }

    public record MemberLoginRequest(@NotBlank @Size(max = 30) String phone) {
    }

    /** The display's Member Sign Up form. */
    public record SignUpRequest(
            @NotBlank @Size(max = 30) String phone,
            @NotBlank @Size(max = 60) String firstName,
            @NotBlank @Size(max = 60) String lastName,
            @Email @Size(max = 254) String email,
            Gender gender,
            // Not in the future: checked when the member is created (CustomerService).
            LocalDate dateOfBirth,
            Boolean marketingEmail,
            Boolean marketingSms,
            Boolean marketingCall) {
    }
}
