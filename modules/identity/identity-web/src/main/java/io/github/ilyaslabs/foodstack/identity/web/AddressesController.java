package io.github.ilyaslabs.foodstack.identity.web;

import io.github.ilyaslabs.evl.EVL;
import io.github.ilyaslabs.evl.Flow;
import java.util.List;

import io.github.ilyaslabs.foodstack.identity.core.application.AddressBook;
import io.github.ilyaslabs.foodstack.identity.core.application.AddressInput;
import io.github.ilyaslabs.foodstack.identity.core.application.SavedAddress;
import io.github.ilyaslabs.foodstack.identity.web.dto.AddressRequest;
import io.github.ilyaslabs.foodstack.identity.web.dto.AddressResponse;
import io.github.ilyaslabs.foodstack.platform.security.CurrentUser;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in customer's saved addresses (PRD ID-6). Each person sees and edits only their own. */
@RestController
@RequestMapping("/api/v1/me/addresses")
class AddressesController {

    private final AddressBook book;

    AddressesController(AddressBook book) {
        this.book = book;
    }

    @GetMapping
    @Flow(value = "identity-addresses", label = "List saved addresses")
    @EVL.Endpoint(method = "GET", path = "/api/v1/me/addresses", caller = "Customer web")
    List<AddressResponse> list() {
        return this.book.list(CurrentUser.id()).stream().map(AddressesController::toResponse).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Flow(value = "identity-address-add", label = "Save an address", description = "Up to 20 per customer")
    @EVL.Endpoint(method = "POST", path = "/api/v1/me/addresses", caller = "Customer web")
    AddressResponse add(@RequestBody AddressRequest request) {
        return toResponse(this.book.add(CurrentUser.id(), toInput(request)));
    }

    @PutMapping("/{addressId}")
    @Flow(value = "identity-address-update", label = "Change a saved address")
    @EVL.Endpoint(method = "PUT", path = "/api/v1/me/addresses/{addressId}", caller = "Customer web")
    AddressResponse update(@PathVariable String addressId, @RequestBody AddressRequest request) {
        return toResponse(this.book.update(CurrentUser.id(), addressId, toInput(request), request.version()));
    }

    @DeleteMapping("/{addressId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Flow(value = "identity-address-remove", label = "Delete a saved address")
    @EVL.Endpoint(method = "DELETE", path = "/api/v1/me/addresses/{addressId}", caller = "Customer web")
    void remove(@PathVariable String addressId) {
        this.book.remove(CurrentUser.id(), addressId);
    }

    private static AddressInput toInput(AddressRequest r) {
        return new AddressInput(r.label(), r.name(), (r.lat() != null) ? r.lat() : Double.NaN,
                (r.lng() != null) ? r.lng() : Double.NaN, r.addressLine(), r.instructions(), Boolean.TRUE.equals(r.preferred()));
    }

    static AddressResponse toResponse(SavedAddress a) {
        return new AddressResponse(a.id(), a.label(), a.name(), a.lat(), a.lng(), a.addressLine(), a.instructions(),
                a.preferred(), a.updatedAt().getEpochSecond(), a.version());
    }
}
