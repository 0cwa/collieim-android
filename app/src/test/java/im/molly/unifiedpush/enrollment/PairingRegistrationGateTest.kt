package im.molly.unifiedpush.enrollment

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.Assert.*
import org.junit.Test

class PairingRegistrationGateTest {
  private val offer = ObjectMapper().readTree(
    (javaClass.getResourceAsStream("/colliesocket-pairing-v1/fixtures/sealed-vectors.json") ?: error("vectors"))
      .bufferedReader().use { it.readText() },
  )[0]["offer"].let {
    PairingV1.parseOffer(it.toString(), 1_799_999_999L).copy(expiresAt = System.currentTimeMillis() / 1000 + 600)
  }

  @Test fun `first use registration is bound to the offer account distributor and instance`() {
    var result: Result<PairingRegistrationGate.Endpoint>? = null
    val binding = PairingRegistrationGate.begin("org.example.distributor", offer, "account-aci", 1) { result = it }!!
    try {
      assertTrue(PairingRegistrationGate.isPairingInstance(binding.instance))
      assertFalse(PairingRegistrationGate.allowsEndpointCallback("default", null))
      assertTrue(PairingRegistrationGate.allowsEndpointCallback("default", null, "default"))
      assertTrue(PairingRegistrationGate.allowsEndpointCallback(binding.instance, null))
      assertTrue(PairingRegistrationGate.blocksLegacyRelay(hasProtectedPairingEndpoint = false))
      assertFalse(PairingRegistrationGate.allowsMessageCallback("default", null, registrationPending = true))
      assertTrue(PairingRegistrationGate.allowsMessageCallback("default", null, registrationPending = true, activeRouteInstance = "default"))
      assertEquals(offer.vapidPublicKey, binding.vapidPublicKey)
      val endpoint = PairingRegistrationGate.Endpoint(
        "https://push.example.test/send/token", "p256dh", "auth", binding.instance,
      )

      assertNull(PairingRegistrationGate.claim("default", "org.example.distributor", "org.example.distributor", "account-aci", 1, endpoint))
      assertNull(PairingRegistrationGate.claim(binding.instance, "org.other", "org.example.distributor", "account-aci", 1, endpoint))
      assertNull(PairingRegistrationGate.claim(binding.instance, "org.example.distributor", "org.example.distributor", "other-account", 1, endpoint))
      assertNull(PairingRegistrationGate.claim(binding.instance, "org.example.distributor", "org.example.distributor", "account-aci", 2, endpoint))
      val wrongOrigin = endpoint.copy(url = "https://other.example.test/send/token")
      assertNull(PairingRegistrationGate.claim(binding.instance, "org.example.distributor", "org.example.distributor", "account-aci", 1, wrongOrigin))

      assertNotNull(PairingRegistrationGate.claim(binding.instance, "org.example.distributor", "org.example.distributor", "account-aci", 1, endpoint))
      assertNull(result)
      assertNotNull(PairingRegistrationGate.current(nowSeconds = offer.expiresAt - 1))
      assertTrue(PairingRegistrationGate.blocksLegacyRelay(hasProtectedPairingEndpoint = false))
      PairingRegistrationGate.complete(binding.instance, endpoint, success = true, nowSeconds = offer.expiresAt - 1)
      assertEquals(endpoint, result?.getOrNull())
      assertNotNull(PairingRegistrationGate.current())
      assertNotNull(PairingRegistrationGate.promote(binding.instance))
      assertNull(PairingRegistrationGate.current())
      assertTrue(PairingRegistrationGate.allowsEndpointCallback(binding.instance, binding.instance))
      assertFalse(PairingRegistrationGate.allowsEndpointCallback("default", binding.instance))
      assertFalse(PairingRegistrationGate.allowsMessageCallback(binding.instance, null, registrationPending = false))
      assertFalse(PairingRegistrationGate.allowsMessageCallback("default", binding.instance, registrationPending = false))
      assertTrue(PairingRegistrationGate.allowsMessageCallback(binding.instance, binding.instance, registrationPending = false))
      assertTrue(PairingRegistrationGate.allowsMessageCallback("default", null, registrationPending = false))
      assertTrue(PairingRegistrationGate.blocksLegacyRelay(hasProtectedPairingEndpoint = true))
    } finally {
      PairingRegistrationGate.cancel(binding.instance)
    }
  }

  @Test fun `expired or stale pairing callbacks cannot enter the ordinary route`() {
    var result: Result<PairingRegistrationGate.Endpoint>? = null
    val binding = PairingRegistrationGate.begin("org.example.distributor", offer, "account-aci", 1) { result = it }!!
    val endpoint = PairingRegistrationGate.Endpoint("https://push.example.test/send/token", "p256dh", "auth", binding.instance)
    try {
      assertNull(PairingRegistrationGate.claim(
        binding.instance, "org.example.distributor", "org.example.distributor", "account-aci", 1, endpoint,
        nowSeconds = offer.expiresAt,
      ))
      assertTrue(result!!.isFailure)
      assertTrue(PairingRegistrationGate.isPairingInstance(binding.instance))
      assertNull(PairingRegistrationGate.current())
      assertFalse(PairingRegistrationGate.allowsEndpointCallback(binding.instance, null))
      assertFalse(PairingRegistrationGate.blocksLegacyRelay(hasProtectedPairingEndpoint = false))
    } finally {
      PairingRegistrationGate.cancel(binding.instance)
    }
  }

  @Test fun `claimed endpoint still expires before registration completion`() {
    var result: Result<PairingRegistrationGate.Endpoint>? = null
    val binding = PairingRegistrationGate.begin("org.example.distributor", offer, "account-aci", 1) { result = it }!!
    val endpoint = PairingRegistrationGate.Endpoint("https://push.example.test/send/token", "p256dh", "auth", binding.instance)
    try {
      assertNotNull(PairingRegistrationGate.claim(
        binding.instance, "org.example.distributor", "org.example.distributor", "account-aci", 1, endpoint,
        nowSeconds = offer.expiresAt - 1,
      ))
      PairingRegistrationGate.complete(binding.instance, endpoint, success = true, nowSeconds = offer.expiresAt)
      assertTrue(result!!.isFailure)
      assertTrue(result!!.exceptionOrNull()!!.message!!.contains("expired"))
      assertNull(PairingRegistrationGate.current())
      assertFalse(PairingRegistrationGate.blocksLegacyRelay(hasProtectedPairingEndpoint = false))
    } finally {
      PairingRegistrationGate.cancel(binding.instance)
    }
  }

  @Test fun `active route callbacks remain usable while candidate source and imported identity are exact`() {
    val binding = PairingRegistrationGate.begin(
      "org.example.distributor", offer, "account-aci", 1,
      previousEndpoint = "https://old.example.test/push", previousP256dh = "old-key", previousAuth = "old-auth",
      previousInstance = "default",
    ) { }!!
    try {
      val endpoint = PairingRegistrationGate.Endpoint(
        "https://push.example.test/send/token", "p256dh", "auth", binding.instance,
      )
      assertTrue("the still-active route keeps its callback", PairingRegistrationGate.allowsEndpointCallback("default", null, "default"))
      assertFalse("a different source cannot replace the active route", PairingRegistrationGate.allowsEndpointCallback("other", null, "default"))
      assertNotNull(PairingRegistrationGate.claim(
        binding.instance, "org.example.distributor", "org.example.distributor", "account-aci", 1, endpoint,
      ))
      assertFalse(PairingRegistrationGate.allowsCandidateConfirmation(
        binding.instance, "org.example.distributor", "org.example.distributor", "account-aci", 1,
      ))
      val identity = PairingRegistrationGate.ImportBinding(
        requestId = "request-1", keyId = "key-1", epoch = 7,
        endpoint = endpoint.url, p256dh = endpoint.p256dh!!, auth = endpoint.auth!!,
        accountId = "account-aci", deviceId = 4, instance = binding.instance,
      )
      assertTrue(PairingRegistrationGate.bindImport(binding.instance, identity))
      assertTrue(PairingRegistrationGate.allowsCandidateConfirmation(
        binding.instance, "org.example.distributor", "org.example.distributor", "account-aci", 1,
      ))
      assertFalse(PairingRegistrationGate.allowsCandidateConfirmation(
        binding.instance, "org.other", "org.example.distributor", "account-aci", 1,
      ))
      assertFalse(PairingRegistrationGate.allowsCandidateConfirmation(
        binding.instance, "org.example.distributor", "org.example.distributor", "other-account", 1,
      ))
    } finally {
      PairingRegistrationGate.cancel(binding.instance)
    }
  }

  @Test fun `receiver import binds a strict v4 identity before candidate confirmation`() {
    val binding = PairingRegistrationGate.begin(
      "org.example.distributor", offer, "account-aci", 1,
      previousEndpoint = "https://old.example.test/push", previousP256dh = "old-key", previousAuth = "old-auth",
    ) { }!!
    try {
      val endpoint = PairingRegistrationGate.Endpoint("https://push.example.test/send/token", "p256dh", "auth", binding.instance)
      assertNotNull(PairingRegistrationGate.claim(binding.instance, "org.example.distributor", "org.example.distributor", "account-aci", 1, endpoint))
      val identity = PairingRegistrationGate.ImportBinding(
        requestId = "f0000000-0000-4000-8000-000000000003", keyId = "", epoch = 1,
        endpoint = endpoint.url, p256dh = endpoint.p256dh!!, auth = endpoint.auth!!,
        accountId = "account-aci", deviceId = 1, instance = binding.instance,
        receiverId = "f0000000-0000-4000-8000-000000000002",
      )
      assertTrue(PairingRegistrationGate.bindImport(binding.instance, identity))
      assertTrue(PairingRegistrationGate.allowsCandidateConfirmation(
        binding.instance, "org.example.distributor", "org.example.distributor", "account-aci", 1,
      ))
      assertFalse(PairingRegistrationGate.bindImport(binding.instance, identity.copy(receiverId = "not-a-uuid")))
      assertFalse(PairingRegistrationGate.bindImport(binding.instance, identity.copy(keyId = "legacy-key")))
      assertFalse(PairingRegistrationGate.bindImport(binding.instance, identity.copy(requestId = "not-a-uuid")))
      assertFalse(PairingRegistrationGate.bindImport(binding.instance, identity.copy(accountId = "other-account")))
    } finally {
      PairingRegistrationGate.cancel(binding.instance)
    }
  }

  @Test fun `locked unregistration after endpoint discards only candidate callback`() {
    val binding = PairingRegistrationGate.begin("org.example.distributor", offer, "account-aci", 1) { }!!
    try {
      PairingRegistrationGate.bufferEndpoint(
        PairingRegistrationGate.BufferedEndpoint("https://push.example.test/send/token", "p256dh", "auth", binding.instance),
      )
      PairingRegistrationGate.bufferUnregistration(binding.instance)
      assertEquals(binding.instance, PairingRegistrationGate.takeBufferedUnregistration())
      assertNull(PairingRegistrationGate.takeBufferedEndpoint())
      assertTrue(PairingRegistrationGate.isRegistrationPending())
    } finally {
      PairingRegistrationGate.cancel(binding.instance)
    }

    val reverse = PairingRegistrationGate.begin("org.example.distributor", offer, "account-aci", 1) { }!!
    try {
      PairingRegistrationGate.bufferUnregistration(reverse.instance)
      PairingRegistrationGate.bufferEndpoint(
        PairingRegistrationGate.BufferedEndpoint("https://push.example.test/send/token", "p256dh", "auth", reverse.instance),
      )
      assertEquals(reverse.instance, PairingRegistrationGate.takeBufferedUnregistration())
      assertNull(PairingRegistrationGate.takeBufferedEndpoint())
      assertTrue(PairingRegistrationGate.isRegistrationPending())
    } finally {
      PairingRegistrationGate.cancel(reverse.instance)
    }
  }

  @Test fun `locked callback gate preserves the scoped route against stale default callbacks`() {
    val protectedInstance = "pairing-v1-current"
    assertFalse(PairingRegistrationGate.allowsLockedCallback("default", protectedInstance))
    assertTrue(PairingRegistrationGate.allowsLockedCallback(protectedInstance, protectedInstance))
    assertTrue(PairingRegistrationGate.allowsLockedCallback("default", null))
  }
}
