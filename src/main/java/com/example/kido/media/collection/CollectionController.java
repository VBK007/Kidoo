package com.example.kido.media.collection;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.example.kido.media.dto.CatalogDtos.ItemPageDto;
import com.example.kido.media.dto.CollectionDtos.CollectionDto;
import com.example.kido.media.dto.CollectionDtos.CollectionListDto;
import com.example.kido.media.dto.CollectionDtos.CollectionRequest;
import com.example.kido.media.web.ActiveProfile;
import com.example.kido.profile.Profile;
import com.example.kido.user.AppUser;

/**
 * Collections — saved searches with names on them.
 *
 * <p>Collections belong to the account, so they are the same list on every device in the
 * house. The active profile still matters: a collection that asks about watch state or
 * likes counts and lists differently per person, which is the whole point of those
 * filters.
 */
@RestController
@RequestMapping("/api/media/collections")
public class CollectionController {

    private final CollectionService service;

    public CollectionController(CollectionService service) {
        this.service = service;
    }

    /**
     * Everything available, split by kind: the ones that ship with the server, the ones
     * this account wrote, and the ones found in the library's own facets.
     */
    @GetMapping
    public CollectionListDto list(@AuthenticationPrincipal AppUser user,
                                  @ActiveProfile Profile profile) {
        return service.list(user, profile);
    }

    /**
     * One collection, by any of the three id forms.
     *
     * @param id a uuid, {@code builtin:<key>} or {@code discovered:<key>}
     */
    @GetMapping("/{id}")
    public CollectionDto get(@AuthenticationPrincipal AppUser user,
                             @ActiveProfile Profile profile,
                             @PathVariable String id) {
        return service.get(user, profile, id);
    }

    /** The titles it holds, paged like the rest of the catalog. */
    @GetMapping("/{id}/items")
    public ItemPageDto items(@AuthenticationPrincipal AppUser user,
                             @ActiveProfile Profile profile,
                             @PathVariable String id,
                             @RequestParam(defaultValue = "0") int page,
                             @RequestParam(defaultValue = "40") int size) {
        return service.items(user, profile, id, page, size);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CollectionDto create(@AuthenticationPrincipal AppUser user,
                                @ActiveProfile Profile profile,
                                @RequestBody CollectionRequest request) {
        return service.create(user, profile, request);
    }

    /**
     * Edits a collection, or pins one.
     *
     * <p>Omitted fields are left alone, so pinning is a one-field request. On a built-in
     * only the name, icon, pin and order may be set — its filter is code — and the row
     * behind it is created by this call, which is why a fresh account has none.
     */
    @PutMapping("/{id}")
    public CollectionDto update(@AuthenticationPrincipal AppUser user,
                                @ActiveProfile Profile profile,
                                @PathVariable String id,
                                @RequestBody CollectionRequest request) {
        return service.update(user, profile, id, request);
    }

    /**
     * Deletes a collection this account wrote. On a built-in this forgets the pin and
     * the rename, restoring it rather than removing it — which is the only sensible
     * reading of deleting something that ships with the server.
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AppUser user, @PathVariable String id) {
        service.delete(user, id);
    }
}
