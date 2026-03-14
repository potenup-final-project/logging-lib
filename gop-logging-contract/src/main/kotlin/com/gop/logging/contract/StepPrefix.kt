package com.gop.logging.contract

object StepPrefix {
    const val PAYMENT_CONFIRM_STEP1 = "payment.confirm.step1"
    const val PAYMENT_CONFIRM_STEP2 = "payment.confirm.step2"
    const val PAYMENT_CANCEL_STEP1 = "payment.cancel.step1"
    const val PAYMENT_CANCEL_STEP2 = "payment.cancel.step2"
    const val OUTBOX_RELAY = "outbox.relay"
    const val BATCH_NETCANCEL = "batch.netcancel"
    const val WEBHOOK_DELIVERY = "webhook.delivery"
    const val SETTLEMENT_LEDGER = "settlement.ledger"
    const val SETTLEMENT_RETRY = "settlement.retry"
    const val BACKOFFICE_PAYMENT = "backoffice.payment"
    const val BACKOFFICE_SETTLEMENT = "backoffice.settlement"
    const val BACKOFFICE_MERCHANT = "backoffice.merchant"
    const val BACKOFFICE_WEBHOOK = "backoffice.webhook"
    const val BACKOFFICE_AUTH = "backoffice.auth"
    const val BACKOFFICE_AUDIT = "backoffice.audit"
}
