const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const RIDER_A = "rider_a_uid";
const RIDER_B = "rider_b_uid";
const DRIVER_1 = "driver_1_uid";
const DRIVER_2 = "driver_2_uid";
const ADMIN_UID = "admin_uid";

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

function nowTs() {
  return new Date(Date.now() - 1000);
}

function validUser(uid) {
  const ts = nowTs();
  return {
    userId: uid,
    fullName: "Tariq Mansour",
    mobileNumber: "+201001234567",
    email: `${uid}@test.com`,
    countryCode: "EG",
    preferredLanguage: "ar",
    activeMode: "RIDER",
    hasRiderProfile: true,
    hasDriverProfile: false,
    isActive: true,
    isSuspended: false,
    emailVerified: true,
    phoneVerified: true,
    riderRatingAvg: 5.0,
    riderRatingCount: 0,
    createdAt: ts,
    updatedAt: ts,
  };
}

function validDriverProfile(uid, approvalStatus = "UNDER_REVIEW") {
  const ts = nowTs();
  return {
    userId: uid,
    fullName: "Karim Hassan",
    mobileNumber: "+201009998877",
    email: `${uid}@test.com`,
    nationalId: "29001010101234",
    licenceNumber: "EG-LIC-99881",
    licenceExpiry: "2029-12-31",
    vehicleMake: "Hyundai",
    vehicleModel: "Elantra",
    vehicleYear: 2023,
    vehicleCategory: "ECONOMY",
    vehicleColor: "Silver",
    registrationNumber: "REG-4455",
    plateNumber: "ABC 1234",
    vehiclePhotoUrls: [],
    approvalStatus,
    driverRatingAvg: 5.0,
    driverRatingCount: 0,
    totalTrips: 0,
    createdAt: ts,
    updatedAt: ts,
  };
}

function validAvailability(uid, approvalStatus = "APPROVED", status = "ONLINE", locTs = 1000000) {
  const ts = nowTs();
  return {
    driverId: uid,
    displayName: "Karim H.",
    vehicleMake: "Hyundai",
    vehicleModel: "Elantra",
    vehicleColor: "Silver",
    plateNumber: "ABC 1234",
    vehicleCategory: "ECONOMY",
    approvalStatus,
    status,
    latitude: 30.0444,
    longitude: 31.2357,
    heading: 90.0,
    locationTimestampMs: locTs,
    activeRideId: "",
    countryCode: "EG",
    city: "Cairo",
    driverRatingAvg: 4.9,
    createdAt: ts,
    updatedAt: ts,
  };
}

function validRide(rideId, riderId) {
  const ts = nowTs();
  return {
    riderId,
    driverId: "",
    riderName: "Tariq Mansour",
    pickupAddress: "Tahrir Square, Cairo",
    pickupLat: 30.0444,
    pickupLng: 31.2357,
    destinationAddress: "Cairo International Airport",
    destinationLat: 30.1128,
    destinationLng: 31.3999,
    countryCode: "EG",
    countryName: "Egypt",
    city: "Cairo",
    currencyCode: "EGP",
    currencySymbol: "EGP",
    currencyDecimals: 2,
    vehicleCategory: "ECONOMY",
    status: "SEARCHING_FOR_DRIVER",
    dispatchRound: 1,
    estimatedDistanceKm: 18.5,
    estimatedDurationMin: 28,
    actualDistanceKm: 0.0,
    waitingMinutes: 0,
    estimatedFareMinor: 11500,
    finalFareMinor: 0,
    pricingVersion: 1,
    pricingPolicyId: "eg_cairo_economy_v1",
    quoteId: "quote_1",
    paymentStatus: "PENDING",
    cancellationFeeMinor: 0,
    riderRated: false,
    driverRated: false,
    createdAt: ts,
    updatedAt: ts,
  };
}

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

test("Unauthenticated users cannot read users, rides, or pricing", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection("users").doc(RIDER_A).get());
  await assertFails(unauthDb.collection("rides").where("riderId", "==", RIDER_A).get());
  await assertFails(unauthDb.collection("pricing_policies").get());
});

test("PII Isolation: Rider B cannot read Rider A profile, saved locations, or rides", async () => {
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    await db.collection("users").doc(RIDER_A).set(validUser(RIDER_A));
    await db.collection("rides").doc("ride_a_1").set(validRide("ride_a_1", RIDER_A));
  });

  const riderBDb = testEnv.authenticatedContext(RIDER_B).firestore();
  await assertFails(riderBDb.collection("users").doc(RIDER_A).get());
  await assertFails(riderBDb.collection("rides").doc("ride_a_1").get());
  await assertFails(riderBDb.collection("rides").where("riderId", "==", RIDER_A).get());
});

test("Shadow Update: Ghost field rejected on user profile", async () => {
  const riderADb = testEnv.authenticatedContext(RIDER_A).firestore();
  const payload = { ...validUser(RIDER_A), isVerifiedAdmin: true };
  await assertFails(riderADb.collection("users").doc(RIDER_A).set(payload));
});

test("Role Escalation: Driver cannot self-approve or switch to DRIVER mode while unapproved", async () => {
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    await db.collection("users").doc(DRIVER_1).set(validUser(DRIVER_1));
    await db.collection("driver_profiles").doc(DRIVER_1).set(validDriverProfile(DRIVER_1, "UNDER_REVIEW"));
    await db.collection("driver_availability").doc(DRIVER_1).set(validAvailability(DRIVER_1, "UNDER_REVIEW", "OFFLINE"));
  });

  const driverDb = testEnv.authenticatedContext(DRIVER_1).firestore();
  // Self-approve attempt fails
  await assertFails(
    driverDb.collection("driver_profiles").doc(DRIVER_1).update({ approvalStatus: "APPROVED" })
  );
  // Switching user activeMode to DRIVER fails when not APPROVED
  await assertFails(
    driverDb.collection("users").doc(DRIVER_1).update({ activeMode: "DRIVER" })
  );
  // Going ONLINE fails when not APPROVED
  await assertFails(
    driverDb.collection("driver_availability").doc(DRIVER_1).update({ status: "ONLINE", approvalStatus: "APPROVED" })
  );
});

test("GPS Monotonicity: Delayed older GPS locationTimestampMs is rejected", async () => {
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    await db.collection("driver_profiles").doc(DRIVER_1).set(validDriverProfile(DRIVER_1, "APPROVED"));
    await db.collection("driver_availability").doc(DRIVER_1).set(validAvailability(DRIVER_1, "APPROVED", "ONLINE", 2000000));
  });

  const driverDb = testEnv.authenticatedContext(DRIVER_1).firestore();
  await assertFails(
    driverDb.collection("driver_availability").doc(DRIVER_1).update({
      latitude: 30.05,
      longitude: 31.24,
      locationTimestampMs: 1500000,
      updatedAt: nowTs(),
    })
  );
  await assertSucceeds(
    driverDb.collection("driver_availability").doc(DRIVER_1).update({
      latitude: 30.05,
      longitude: 31.24,
      locationTimestampMs: 2005000,
      updatedAt: nowTs(),
    })
  );
});

test("Atomic Assignment: Offered approved driver wins in batch, non-offered driver fails", async () => {
  const nowMs = Date.now();
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    await db.collection("driver_profiles").doc(DRIVER_1).set(validDriverProfile(DRIVER_1, "APPROVED"));
    await db.collection("driver_availability").doc(DRIVER_1).set(validAvailability(DRIVER_1, "APPROVED", "ONLINE"));
    await db.collection("driver_profiles").doc(DRIVER_2).set(validDriverProfile(DRIVER_2, "APPROVED"));
    await db.collection("driver_availability").doc(DRIVER_2).set(validAvailability(DRIVER_2, "APPROVED", "ONLINE"));
    await db.collection("rides").doc("ride_1").set(validRide("ride_1", RIDER_A));
    await db.collection("ride_offers").doc(`ride_1_${DRIVER_1}`).set({
      rideId: "ride_1",
      riderId: RIDER_A,
      driverId: DRIVER_1,
      round: 1,
      status: "PENDING",
      pickupAddress: "Tahrir Square",
      pickupLat: 30.0444,
      pickupLng: 31.2357,
      destinationAddress: "Cairo Airport",
      destinationLat: 30.1128,
      destinationLng: 31.3999,
      distanceToPickupKm: 1.2,
      estimatedTripDistanceKm: 18.5,
      estimatedDurationMin: 28,
      estimatedFareMinor: 11500,
      currencyCode: "EGP",
      currencySymbol: "EGP",
      currencyDecimals: 2,
      vehicleCategory: "ECONOMY",
      riderRatingAvg: 5.0,
      issuedAtMs: nowMs,
      expiresAtMs: nowMs + 30000,
      createdAt: nowTs(),
      updatedAt: nowTs(),
    });
  });

  // Non-offered driver 2 cannot read or accept ride_1
  const driver2Db = testEnv.authenticatedContext(DRIVER_2).firestore();
  await assertFails(driver2Db.collection("rides").doc("ride_1").get());

  // Offered driver 1 accepts atomically via batch (offer + ride + availability)
  const driver1Db = testEnv.authenticatedContext(DRIVER_1).firestore();
  const batch = driver1Db.batch();
  batch.update(driver1Db.collection("rides").doc("ride_1"), {
    driverId: DRIVER_1,
    driverName: "Karim Hassan",
    driverPlate: "ABC 1234",
    driverVehicle: "Silver Hyundai Elantra",
    status: "ACCEPTED",
    updatedAt: nowTs(),
  });
  batch.update(driver1Db.collection("ride_offers").doc(`ride_1_${DRIVER_1}`), {
    status: "ACCEPTED",
    respondedAtMs: nowMs + 2000,
    updatedAt: nowTs(),
  });
  batch.update(driver1Db.collection("driver_availability").doc(DRIVER_1), {
    status: "ACCEPTED",
    activeRideId: "ride_1",
    updatedAt: nowTs(),
  });
  await assertSucceeds(batch.commit());
});

test("Email Spoof Attack & Admin RBAC: unverified admin email rejected; verified admin allowed", async () => {
  const ts = nowTs();
  const policy = {
    countryCode: "EG",
    countryName: "Egypt",
    city: "Cairo",
    currencyCode: "EGP",
    currencySymbol: "EGP",
    currencyDecimals: 2,
    serviceCategory: "ECONOMY",
    isTaxiReference: false,
    baseFareMinor: 1200,
    includedDistanceKm: 1.0,
    pricePerKmMinor: 650,
    waitingChargePerMinMinor: 100,
    minimumFareMinor: 2500,
    bookingFeeMinor: 300,
    taxPercentage: 14.0,
    airportSurchargeMinor: 2000,
    nightSurchargeMinor: 500,
    trafficSurchargeMinor: 0,
    surgeMultiplier: 1.0,
    effectiveStartMs: 1000,
    effectiveEndMs: 9999999999999,
    pricingVersion: 1,
    isActive: true,
    updatedByAdminId: ADMIN_UID,
    createdAt: ts,
    updatedAt: ts,
  };

  // Spoofed unverified email must FAIL
  const spoofDb = testEnv.authenticatedContext("spoof_uid", {
    email: "moh.khattab23@gmail.com",
    email_verified: false,
  }).firestore();
  await assertFails(spoofDb.collection("pricing_policies").doc("eg_cairo_econ").set(policy));

  // Regular rider must FAIL
  const riderDb = testEnv.authenticatedContext(RIDER_A).firestore();
  await assertFails(riderDb.collection("pricing_policies").doc("eg_cairo_econ").set(policy));

  // Verified admin email must SUCCEED
  const adminDb = testEnv.authenticatedContext(ADMIN_UID, {
    email: "moh.khattab23@gmail.com",
    email_verified: true,
  }).firestore();
  await assertSucceeds(adminDb.collection("pricing_policies").doc("eg_cairo_econ").set(policy));
});

test("Payments: Client cannot forge SUCCEEDED payment directly", async () => {
  const ts = nowTs();
  const riderDb = testEnv.authenticatedContext(RIDER_A).firestore();
  await assertFails(
    riderDb.collection("payments").doc("pay_1").set({
      rideId: "ride_1",
      riderId: RIDER_A,
      driverId: DRIVER_1,
      idempotencyKey: "idem_1",
      stripePaymentIntentId: "pi_fake",
      paymentMethodType: "CARD",
      currencyCode: "EGP",
      currencyDecimals: 2,
      amountMinor: 10000,
      refundedAmountMinor: 0,
      platformFeeMinor: 1500,
      taxMinor: 1400,
      conversionRate: 1.0,
      settlementCurrency: "EGP",
      settlementAmountMinor: 10000,
      conversionTimestampMs: 1000,
      conversionSource: "local",
      status: "SUCCEEDED",
      receiptNumber: "RCP-1001",
      createdAt: ts,
      updatedAt: ts,
    })
  );
});

test("Same-second Competing Offer Revocation: winning driver revokes losing zone offers and rejects pre-assignment revocation", async () => {
  const nowMs = Date.now();
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    await db.collection("driver_profiles").doc(DRIVER_1).set(validDriverProfile(DRIVER_1, "APPROVED"));
    await db.collection("driver_availability").doc(DRIVER_1).set(validAvailability(DRIVER_1, "APPROVED", "ONLINE"));
    await db.collection("driver_profiles").doc(DRIVER_2).set(validDriverProfile(DRIVER_2, "APPROVED"));
    await db.collection("driver_availability").doc(DRIVER_2).set(validAvailability(DRIVER_2, "APPROVED", "ONLINE"));
    await db.collection("rides").doc("ride_zone_1").set({
      ...validRide("ride_zone_1", RIDER_A),
      offeredDriverIds: [DRIVER_1, DRIVER_2],
    });
    const baseOffer = {
      rideId: "ride_zone_1",
      riderId: RIDER_A,
      round: 1,
      status: "PENDING",
      pickupAddress: "Tahrir Square",
      pickupLat: 30.0444,
      pickupLng: 31.2357,
      destinationAddress: "Cairo Airport",
      destinationLat: 30.1128,
      destinationLng: 31.3999,
      distanceToPickupKm: 0.8,
      estimatedTripDistanceKm: 18.5,
      estimatedDurationMin: 28,
      estimatedFareMinor: 11500,
      currencyCode: "EGP",
      currencySymbol: "EGP",
      currencyDecimals: 2,
      vehicleCategory: "ECONOMY",
      riderRatingAvg: 5.0,
      issuedAtMs: nowMs,
      expiresAtMs: nowMs + 30000,
      createdAt: nowTs(),
      updatedAt: nowTs(),
    };
    await db.collection("ride_offers").doc(`ride_zone_1_${DRIVER_1}`).set({ ...baseOffer, driverId: DRIVER_1 });
    await db.collection("ride_offers").doc(`ride_zone_1_${DRIVER_2}`).set({ ...baseOffer, driverId: DRIVER_2 });
  });

  const driver1Db = testEnv.authenticatedContext(DRIVER_1).firestore();
  const driver2Db = testEnv.authenticatedContext(DRIVER_2).firestore();

  // Before anyone accepts, Driver 1 CANNOT revoke Driver 2's offer
  await assertFails(
    driver1Db.collection("ride_offers").doc(`ride_zone_1_${DRIVER_2}`).update({
      status: "CANCELLED_BY_OTHER",
      updatedAt: nowTs(),
    })
  );

  // Driver 1 accepts atomically within 30s window
  const batch = driver1Db.batch();
  batch.update(driver1Db.collection("rides").doc("ride_zone_1"), {
    driverId: DRIVER_1,
    driverName: "Karim Hassan",
    driverPlate: "ABC 1234",
    driverVehicle: "Silver Hyundai Elantra",
    status: "ACCEPTED",
    updatedAt: nowTs(),
  });
  batch.update(driver1Db.collection("ride_offers").doc(`ride_zone_1_${DRIVER_1}`), {
    status: "ACCEPTED",
    respondedAtMs: nowMs + 1000,
    updatedAt: nowTs(),
  });
  batch.update(driver1Db.collection("driver_availability").doc(DRIVER_1), {
    status: "ACCEPTED",
    activeRideId: "ride_zone_1",
    updatedAt: nowTs(),
  });
  await assertSucceeds(batch.commit());

  // Immediately in the same second, Driver 1 revokes Driver 2's competing offer
  await assertSucceeds(
    driver1Db.collection("ride_offers").doc(`ride_zone_1_${DRIVER_2}`).update({
      status: "CANCELLED_BY_OTHER",
      updatedAt: nowTs(),
    })
  );

  // And Driver 2 cannot accept the already-revoked offer
  await assertFails(
    driver2Db.collection("ride_offers").doc(`ride_zone_1_${DRIVER_2}`).update({
      status: "ACCEPTED",
      respondedAtMs: nowMs + 1500,
      updatedAt: nowTs(),
    })
  );
});

test("Trip History: rider and driver can list their own completed rides; unrelated driver denied", async () => {
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    await db.collection("rides").doc("ride_hist_1").set({
      ...validRide("ride_hist_1", RIDER_A),
      driverId: DRIVER_1,
      driverName: "Karim Hassan",
      driverPlate: "ABC 1234",
      driverVehicle: "Hyundai Elantra",
      status: "COMPLETED",
      actualDistanceKm: 18.2,
      finalFareMinor: 12450,
    });
  });

  const riderADb = testEnv.authenticatedContext(RIDER_A).firestore();
  const driver1Db = testEnv.authenticatedContext(DRIVER_1).firestore();
  const driver2Db = testEnv.authenticatedContext(DRIVER_2).firestore();

  const riderSnap = await assertSucceeds(
    riderADb.collection("rides").where("riderId", "==", RIDER_A).get()
  );
  assert.equal(riderSnap.size, 1);

  const driver1Snap = await assertSucceeds(
    driver1Db.collection("rides").where("driverId", "==", DRIVER_1).get()
  );
  assert.equal(driver1Snap.size, 1);

  await assertFails(
    driver2Db.collection("rides").where("driverId", "==", DRIVER_1).get()
  );
});

test("FCM Tokens: owner can register and update own token; cross-user and invalid token writes denied", async () => {
  const riderADb = testEnv.authenticatedContext(RIDER_A).firestore();
  const riderBDb = testEnv.authenticatedContext(RIDER_B).firestore();
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  const ts = nowTs();

  const validToken = {
    userId: RIDER_A,
    token: "fcm_token_sample_android_device_1234567890",
    platform: "ANDROID",
    deviceModel: "Pixel 9",
    appVersion: "1.0",
    activeMode: "RIDER",
    preferredLanguage: "en",
    notificationsEnabled: true,
    createdAt: ts,
    updatedAt: ts,
  };

  await assertSucceeds(
    riderADb.collection("users").doc(RIDER_A).collection("fcm_tokens").doc("tok_device_1").set(validToken)
  );

  // Owner can update activeMode / notificationsEnabled without modifying createdAt
  await assertSucceeds(
    riderADb.collection("users").doc(RIDER_A).collection("fcm_tokens").doc("tok_device_1").update({
      activeMode: "DRIVER",
      notificationsEnabled: false,
      updatedAt: nowTs(),
    })
  );

  // Cross-user read and write must fail
  await assertFails(
    riderBDb.collection("users").doc(RIDER_A).collection("fcm_tokens").doc("tok_device_1").get()
  );
  await assertFails(
    riderBDb.collection("users").doc(RIDER_A).collection("fcm_tokens").doc("tok_device_1").update({
      notificationsEnabled: true,
      updatedAt: nowTs(),
    })
  );

  // Unauthenticated read must fail
  await assertFails(
    unauthDb.collection("users").doc(RIDER_A).collection("fcm_tokens").get()
  );

  // Short invalid token rejected
  await assertFails(
    riderADb.collection("users").doc(RIDER_A).collection("fcm_tokens").doc("tok_bad").set({
      ...validToken,
      token: "short",
    })
  );
});

test("Post-Trip Ratings: bidirectional rider/driver feedback allowed on completed rides; self-rating & spoofing denied", async () => {
  const ts = nowTs();
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    await db.collection("rides").doc("ride_done_1").set({
      ...validRide("ride_done_1", RIDER_A),
      driverId: DRIVER_1,
      driverName: "Karim Hassan",
      driverPlate: "ABC 1234",
      driverVehicle: "Hyundai Elantra",
      status: "COMPLETED",
      actualDistanceKm: 12.0,
      finalFareMinor: 9500,
    });
    await db.collection("rides").doc("ride_open_2").set({
      ...validRide("ride_open_2", RIDER_A),
      driverId: DRIVER_1,
      status: "IN_PROGRESS",
    });
  });

  const riderADb = testEnv.authenticatedContext(RIDER_A).firestore();
  const driver1Db = testEnv.authenticatedContext(DRIVER_1).firestore();
  const intruderDb = testEnv.authenticatedContext(RIDER_B).firestore();

  // Rider rates Driver on completed ride (atomic batch: rating doc + riderRated flag)
  const riderBatch = riderADb.batch();
  riderBatch.set(riderADb.collection("ratings").doc("rat_ride_done_1_rider"), {
    rideId: "ride_done_1",
    fromUserId: RIDER_A,
    toUserId: DRIVER_1,
    fromRole: "RIDER",
    stars: 5,
    comment: "Very smooth drive and polite driver.",
    tags: ["CLEAN_VEHICLE", "SMOOTH_DRIVE"],
    raterName: "Tariq Mansour",
    createdAt: ts,
  });
  riderBatch.update(riderADb.collection("rides").doc("ride_done_1"), {
    riderRated: true,
    updatedAt: nowTs(),
  });
  await assertSucceeds(riderBatch.commit());

  // Driver rates Rider on the same completed ride
  const driverBatch = driver1Db.batch();
  driverBatch.set(driver1Db.collection("ratings").doc("rat_ride_done_1_driver"), {
    rideId: "ride_done_1",
    fromUserId: DRIVER_1,
    toUserId: RIDER_A,
    fromRole: "DRIVER",
    stars: 4,
    comment: "Ready at pickup point.",
    tags: ["PUNCTUAL_RIDER", "FRIENDLY_RIDER"],
    raterName: "Karim Hassan",
    createdAt: ts,
  });
  driverBatch.update(driver1Db.collection("rides").doc("ride_done_1"), {
    driverRated: true,
    updatedAt: nowTs(),
  });
  await assertSucceeds(driverBatch.commit());

  // Recipient can list ratings addressed to them
  const driverReceived = await assertSucceeds(
    driver1Db.collection("ratings").where("toUserId", "==", DRIVER_1).get()
  );
  assert.equal(driverReceived.size, 1);

  // Unrelated user cannot read or submit rating for someone else's ride
  await assertFails(
    intruderDb.collection("ratings").where("toUserId", "==", DRIVER_1).get()
  );
  await assertFails(
    intruderDb.collection("ratings").doc("rat_ride_done_1_rider").set({
      rideId: "ride_done_1",
      fromUserId: RIDER_B,
      toUserId: DRIVER_1,
      fromRole: "RIDER",
      stars: 1,
      comment: "Fake review",
      createdAt: ts,
    })
  );

  // Self-rating and non-completed ride rating denied
  await assertFails(
    riderADb.collection("ratings").doc("rat_ride_open_2_rider").set({
      rideId: "ride_open_2",
      fromUserId: RIDER_A,
      toUserId: DRIVER_1,
      fromRole: "RIDER",
      stars: 5,
      comment: "Not completed yet",
      createdAt: ts,
    })
  );
  await assertFails(
    riderADb.collection("ratings").doc("rat_ride_done_1_driver").set({
      rideId: "ride_done_1",
      fromUserId: RIDER_A,
      toUserId: RIDER_A,
      fromRole: "RIDER",
      stars: 5,
      comment: "Self boost",
      createdAt: ts,
    })
  );
});




