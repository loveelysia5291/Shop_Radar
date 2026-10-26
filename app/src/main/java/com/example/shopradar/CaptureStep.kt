package com.example.shopradar

/** Fixed app-side markers: never put webpage text or form values into the trace. */
internal enum class CaptureStep {
    LOADING, PAGE_STARTED, PAGE_COMMITTED, PAGE_FINISHED, EXTRACTING, PARSED,
    AUTH_REQUIRED, CONSENT_REQUIRED, WAITING_PRODUCTS, SUCCESS, TIMEOUT, STOPPED,
    EVALUATION_FAILED, LOAD_FAILED
}
