package com.example.kido.user;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<AppUser, String> {
    Optional<AppUser> findByUsername(String username);

    Optional<AppUser> findByEmail(String email);

    Optional<AppUser> findByUsernameOrEmail(String username, String email);

    boolean existsByUsername(String username);

    boolean existsByEmail(String email);

    // --- admin dashboard tallies ---

    long countByRole(Role role);

    /** Signups inside a window, for the growth tiles. */
    long countByCreatedAtGreaterThanEqual(Instant since);

    /**
     * Paying accounts, counted the same way {@link Subscription#isActive()} decides it:
     * a flag left on after the term ran out is not a paying customer, and a dashboard
     * that counted it as one would overstate revenue every month.
     */
    @Query("""
            select count(u) from AppUser u
            where u.subscription.premium = true and u.subscription.premiumUntil > :now
            """)
    long countActivePremium(@Param("now") Instant now);
}
