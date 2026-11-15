package io.github.ilyaslabs.foodstack.payment.core.persistence;

import io.github.ilyaslabs.evl.EVL;
import org.springframework.data.mongodb.repository.MongoRepository;

@EVL.Database("foodstack_payment")
public interface PaymentRepository extends MongoRepository<PaymentDocument, String> {
}
