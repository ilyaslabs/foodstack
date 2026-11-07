package io.github.ilyaslabs.foodstack.ledger.core.domain;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import io.github.ilyaslabs.foodstack.geo.api.DeliveryMode;
import io.github.ilyaslabs.foodstack.order.api.OrderMoney;
import io.github.ilyaslabs.foodstack.platform.core.PaymentMethod;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PostingsTest {

    /** Food 1000.00, tax 50.00, delivery 150.00, service fee 20.00, tip 30.00; 10% + 5.00 commission. */
    private static OrderMoney money(DeliveryMode mode, long itemsDiscount, long deliveryDiscount, long operatorShare,
            String collectedBy) {
        return new OrderMoney("brand-1", mode, collectedBy, 100_000, itemsDiscount, 15_000, deliveryDiscount, 2_000, 5_000,
                3_000, operatorShare, 1_000, 500);
    }

    private static Map<String, Long> byAccount(List<Leg> legs) {
        return legs.stream().collect(Collectors.groupingBy(Leg::accountId, Collectors.summingLong(Leg::amountMinor)));
    }

    private static long sum(List<Leg> legs) {
        return legs.stream().mapToLong(Leg::amountMinor).sum();
    }

    @Test
    @DisplayName("commission is a percentage of the food after the discounts the store funds, plus the fixed amount")
    void commission() {
        assertThat(Postings.commission(money(DeliveryMode.SELF, 0, 0, 0, OrderMoney.OPERATOR))).isEqualTo(10_000 + 500);
        // store-funded 200.00 off the food: 10% of 800.00
        assertThat(Postings.commission(money(DeliveryMode.SELF, 20_000, 0, 0, OrderMoney.OPERATOR))).isEqualTo(8_000 + 500);
        // operator funds all of it: the store's food is still 1000.00
        assertThat(Postings.commission(money(DeliveryMode.SELF, 20_000, 0, 20_000, OrderMoney.OPERATOR))).isEqualTo(10_500);
        // half funded by the operator, split over food and delivery pro rata (200 food + 100 delivery, operator 150)
        assertThat(Postings.storeItemsDiscount(money(DeliveryMode.OPERATOR, 20_000, 10_000, 15_000, OrderMoney.OPERATOR)))
            .isEqualTo(10_000);
        assertThat(Postings.percent(12_345, 1_000)).isEqualTo(1_235);
        assertThat(Postings.percent(0, 1_000)).isZero();
        assertThat(Postings.percent(1_000, 0)).isZero();
    }

    @Test
    @DisplayName("card order delivered by the store: the branch gets food, tax, delivery and tip less commission; the operator its fee")
    void selfDeliveryCard() {
        OrderMoney m = money(DeliveryMode.SELF, 0, 0, 0, OrderMoney.OPERATOR);
        List<Leg> legs = Postings.orderCompleted("s1", PaymentMethod.CARD, m);
        Map<String, Long> by = byAccount(legs);
        assertThat(sum(legs)).isZero();
        assertThat(by.get(Accounts.PREPAID)).isEqualTo(-m.totalMinor());
        assertThat(by.get("store:s1")).isEqualTo(100_000 + 5_000 + 15_000 + 3_000 - 10_500);
        assertThat(by.get(Accounts.COMMISSION)).isEqualTo(10_500);
        assertThat(by.get(Accounts.SERVICE_FEE)).isEqualTo(2_000);
        assertThat(by).doesNotContainKeys(Accounts.DELIVERY, Accounts.RIDER_POOL, Accounts.DISCOUNTS);
    }

    @Test
    @DisplayName("cash order delivered by an operator rider: the cash and the tip wait for dispatch, the delivery charge is the operator's")
    void operatorDeliveryCash() {
        OrderMoney m = money(DeliveryMode.OPERATOR, 0, 0, 0, OrderMoney.OPERATOR);
        List<Leg> legs = Postings.orderCompleted("s1", PaymentMethod.CASH, m);
        Map<String, Long> by = byAccount(legs);
        assertThat(sum(legs)).isZero();
        assertThat(by.get(Accounts.CASH)).isEqualTo(-m.totalMinor());
        assertThat(by.get(Accounts.DELIVERY)).isEqualTo(15_000);
        assertThat(by.get(Accounts.RIDER_POOL)).isEqualTo(3_000);
        assertThat(by.get("store:s1")).isEqualTo(100_000 + 5_000 - 10_500);

        List<Leg> delivery = Postings.deliveryCompleted("r1", "OPERATOR", 10_500, 3_000, m.totalMinor());
        Map<String, Long> after = byAccount(delivery);
        assertThat(sum(delivery)).isZero();
        assertThat(after.get("rider:r1")).isEqualTo(10_500 + 3_000 - m.totalMinor());
        assertThat(after.get(Accounts.RIDER_PAY)).isEqualTo(-10_500);
        // both clearing accounts are back to zero once the rider is known
        assertThat(by.get(Accounts.CASH) + after.get(Accounts.CASH)).isZero();
        assertThat(by.get(Accounts.RIDER_POOL) + after.get(Accounts.RIDER_POOL)).isZero();
    }

    @Test
    @DisplayName("cash the branch takes itself (pickup or its own rider) leaves the branch owing the operator its share")
    void storeCash() {
        OrderMoney m = new OrderMoney("b", null, OrderMoney.OPERATOR, 100_000, 0, 0, 0, 2_000, 5_000, 0, 0, 1_000, 0);
        List<Leg> legs = Postings.orderCompleted("s1", PaymentMethod.CASH, m);
        assertThat(sum(legs)).isZero();
        // holds 1070.00, keeps 1050.00 less 100.00 commission: owes the operator 120.00
        assertThat(byAccount(legs).get("store:s1")).isEqualTo(-12_000);
        assertThat(legs).anyMatch((l) -> l.kind().equals(Kinds.CASH) && l.amountMinor() == -107_000);
    }

    @Test
    @DisplayName("discounts are funded by the store and the operator as the coupon said")
    void discounts() {
        OrderMoney m = money(DeliveryMode.OPERATOR, 20_000, 10_000, 15_000, OrderMoney.OPERATOR);
        List<Leg> legs = Postings.orderCompleted("s1", PaymentMethod.CARD, m);
        Map<String, Long> by = byAccount(legs);
        assertThat(sum(legs)).isZero();
        assertThat(by.get(Accounts.DISCOUNTS)).isEqualTo(-15_000);
        long commission = Postings.percent(100_000 - 10_000, 1_000) + 500;
        assertThat(by.get(Accounts.COMMISSION)).isEqualTo(commission);
        assertThat(by.get("store:s1")).isEqualTo(100_000 + 5_000 - 15_000 - commission);
        // a share above the discount is capped at the discount
        assertThat(sum(Postings.orderCompleted("s1", PaymentMethod.CARD, money(DeliveryMode.SELF, 1_000, 0, 9_999,
                OrderMoney.OPERATOR)))).isZero();
    }

    @Test
    @DisplayName("direct collection: the branch holds the whole amount and owes the operator commission, fee and its delivery")
    void directCollection() {
        OrderMoney m = money(DeliveryMode.OPERATOR, 0, 0, 0, OrderMoney.STORE);
        List<Leg> legs = Postings.orderCompleted("s1", PaymentMethod.CARD, m);
        Map<String, Long> by = byAccount(legs);
        assertThat(sum(legs)).isZero();
        assertThat(by).doesNotContainKey(Accounts.PREPAID);
        // receivable from the store: commission, delivery charge, service fee and the rider's tip (PRD DP-5)
        assertThat(by.get("store:s1")).isEqualTo(-(10_500 + 15_000 + 2_000 + 3_000));
    }

    @Test
    @DisplayName("refunds come out of whoever support chose, and out of the card money or the branch's own account")
    void refunds() {
        Map<String, Long> store = byAccount(Postings.refundFunded("s1", 5_000, "STORE", OrderMoney.OPERATOR));
        assertThat(store).containsEntry("store:s1", -5_000L).containsEntry(Accounts.PREPAID, 5_000L);
        Map<String, Long> operator = byAccount(Postings.refundFunded("s1", 5_000, "OPERATOR", OrderMoney.OPERATOR));
        assertThat(operator).containsEntry(Accounts.REFUNDS, -5_000L).doesNotContainKey("store:s1");
        Map<String, Long> direct = byAccount(Postings.refundFunded("s1", 5_000, "OPERATOR", OrderMoney.STORE));
        assertThat(direct).containsEntry(Accounts.REFUNDS, -5_000L).containsEntry("store:s1", 5_000L);
        // the branch paid it back from its own account and bears it: nothing changes between it and the operator
        assertThat(byAccount(Postings.refundFunded("s1", 5_000, "STORE", OrderMoney.STORE))).containsEntry("store:s1", 0L);
    }

    @Test
    @DisplayName("card money in and out of the gateway, store riders, settlements and the balance check")
    void others() {
        assertThat(byAccount(Postings.paymentReceived(9_000))).containsEntry(Accounts.GATEWAY, -9_000L)
            .containsEntry(Accounts.PREPAID, 9_000L);
        assertThat(byAccount(Postings.refundPaidOut(9_000))).containsEntry(Accounts.GATEWAY, 9_000L)
            .containsEntry(Accounts.PREPAID, -9_000L);
        assertThat(Postings.deliveryCompleted("r1", "STORE", 0, 300, 0)).isEmpty();
        assertThat(Postings.deliveryCompleted(null, "OPERATOR", 100, 0, 0)).isEmpty();
        assertThat(byAccount(Postings.settlement("store:s1", 4_200))).containsEntry("store:s1", -4_200L)
            .containsEntry(Accounts.BANK, 4_200L);
        Postings.Builder unbalanced = new Postings.Builder();
        unbalanced.add("a", Kinds.FOOD, 1);
        assertThatThrownBy(unbalanced::balanced).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("account ids name their party")
    void accounts() {
        assertThat(Accounts.partyType("store:s1")).isEqualTo(Accounts.STORE);
        assertThat(Accounts.partyType("rider:r1")).isEqualTo(Accounts.RIDER);
        assertThat(Accounts.partyType(Accounts.COMMISSION)).isEqualTo(Accounts.OPERATOR);
        assertThat(Accounts.partyType(Accounts.GATEWAY)).isEqualTo(Accounts.CLEARING);
        assertThat(Accounts.partyId("store:s1")).isEqualTo("s1");
        assertThat(Accounts.partyId(Accounts.COMMISSION)).isEqualTo(Accounts.COMMISSION);
    }
}
