# M3d P-03 — Commerce capture application plan

Spec: [payment](slice-3-payment.md), builderCLEAR. Base ff6857b4; P02Catalogconsume64focusedPASS/Commercecompat41PASS, not wholeM3d accepted. Canonical slice-3-payment. Implementation uses TDD with actualRED thenGREEN; no commit/push/nativeDB changes.

## Exact interfaces and files

Proto contracts/proto/agora/commerce/v1/commerce_payments.proto and libraries/commerce-contract exist. Reuse Identity validation and existing HTTP orders; public PaymentHTTP belongs Paymentservice later. New CommercePayments listener9094 optional COMMERCE_GRPC_ENABLED(defaultfalse)/PORT9094, internal token before UUID/persistence, max4KiBinbound/outbound, sane port/token lifecycle.

New domain/application/store classes for CaptureApplication and Order payment transitions; Jdbc store local transactions only, GrpcReservationSettlement caller existingCatalogReservations.Get/Consume with128KiB bound, server adapter/lifecycle. Modify JdbcCheckoutStore Order reads/completion for storedstate, application.yaml/pom/config fornewRPC, V3__order_payments.sql. ExistingOrderHTTP stateenum canonical1.1.0 alreadyextendedPAYMENT_PROCESSING/PAID; frontendP06pending. Keep all existingcheckout/cart interfaces and failclosed defaults.

V3 orders.state defaultAWAITING_PAYMENT, winner_capture_id nullable. Migrate oldorders toAWAITING_PAYMENT, not EXPIRED bylocalclock. Capture receipts permanent captureId+hash buyer/order/attempt/providerTxnNo/amount/currency; FK orderlocalonly; actor checks beforehash mismatch, uniqueglobalcaptureId and orderwinner. Capturejob lease30s/revision,batch20/10s; no network underrowlock. receipts PROCESSING/APPLIED/REFUND_REQUIRED, typedreason +needs_reconciliation flag.

GetPayableOrder ownedlocalorder + freshCatalogGet without localTX. Only HELD and localnonterminaleligibleAWAITING can return trustedprice/expiry; CatalogRELEASED→storeEXPIREDguarded and rejectnewpayment. CONSUMED/unknown cannotcreatefreshlink; noTerminalregression. Payment caller handles itsownkeyreplay first, noCommercedependency neededforPaymentGETrecovery.

ApplyCapture validates capture/attempt/buyer/orderUUID, txnNo1–15digitsnonzero, currencyVND, positiveboundedamount. Receiptpreflight bycaptureId must preserveimmutablehash; crossowner/order mismatch failclosed. Underorderlock: terminalEXPIRED→REFUND_REQUIRED keepEXPIRED, PAIDnewcapture→REFUND_REQUIRED keepPAID, amountmismatch→REFUND_REQUIRED withoutselectingwinner. Otherwise chooseonepermanentwinner or markduplicatecaptureREFUND_REQUIRED. Save receipt+processingstate/job beforeConsume. Existingwinner never replaced by timeout/failure.

Call Consume exact buyer/checkout/capture outsideTX, validate commonReservationResponse including capturemetadata, expiry900s, snapshotitems/binding and state-specificmetadata/reason. CONSUMEDsamecapture→PAID+APPLIEDsameTX/revisionfence; RELEASED→EXPIRED+REFUND_REQUIRED. RPCunknown/NOT_FOUND/CONSUMEDothercapture/counterconflict never triggersrefund; staysPROCESSING (flag reconciliation for permanentconflict), preservesmoneyidentity. Do not callRelease as compensation. GetCaptureApplication notfound isnotabsenceproof; retryApplysamepayload.

Expiry/recoveryworker readsCatalog authority, notCommerceclockterminal. HELD stayspending; RELEASED canmarkEXPIRED (neveroverwritePAID), CONSUMED mustsettle matchingreceipt instead ofexpire. Public Order reads remainlocalPG only and return storedstate; dependencyoutage mustnotblock snapshot. Deadline onlyschedulinghint, explicitorderExpiresAt preserved.

Range5000..9999999999VND: rejectnewpreview afterCart/Catalogquote butbeforedurablepreviewwrite; replayexistingpreview canreturnoldprojection. Claim validates previouslyunclaimedpreview total beforeattempt/Hold; existingattemptreplay/reconciliation bypassrangeguard. Legacyunsupportedordersstillread andPaymentinitiation rejects clearly. No clienttotal.

## Verification steps

1. Write realPG/HTTP regression proving localdeadline alone no longerEXPIRED (expectedcurrentRED); observe beforecode. AddV3schema/receipt/gRPC firsttests and fulfill minimalpath.
2. TDD winnercapture→Consume→PAID and replay; capturemustbedurablebeforenetwork, noactiveDBconnectionduringRPC. RPCtoken/UUID/size/owner/hash safeerror tests.
3. SimulatedlostConsume response afterremotesuccess→PROCESSINGthenreplaysamecapture→PAID, notrefund. Stalelease cannotoverwriteorder/receipt/cart; completionSQLfailrollbacksstate, retryrecovers.
4. Concurrentfirstcaptures winner1/loserrefund, latecapture afterauthoritativeEXPIRED noOrderreopen, amountmismatchnoConsume, badowner404, duplicatecapturehashmismatch. WorkerexpiryvsConsume raceholdsPAIDterminal, foreignConsumed markreconciliation. Localclockskew nofalseEXPIRED.
5. ExistingCheckoutAdmission tests intentionallyupdate oldexpiry-on-read assertions to stored-authority semantics; addauthoritativeRELEASEDworker transition proof. Do not merelyremoveassertions. OldCarttests unchanged. Newrangeboundtests newpreview/unclaimedlegacy/replayexistingPROCESSING.
6. FullCommerce module+focusedprotocol tests, releaseMaven lease, actualcode review. Main laterroot/live5app and frontendstates. This isCommercefoundation, notrealVNPAYpayment.

Main owns rootpom/contracts and Paymentmodule; worker owns Commerce module only (includingnewtests/fixtures). Do not edit Catalog/Payment/frontend/sharedcontracts. Maven1owner sharedcontracttargets. Logs/tmp/agora-m3d-commerce-*.log. Exacttestcount onlyafterrun; noauto stage/commit.
