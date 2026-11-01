package io.github.ilyaslabs.foodstack.payment.core.persistence;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface PaymentRepository extends MongoRepository<PaymentDocument, String> {
}
