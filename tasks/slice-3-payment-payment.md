# M3d P-04/P-05 — Payment implementation plan

Spec: [M3d](slice-3-payment.md), GaussdesignCLEAR. Canonical slice-3-payment. Main owns Paymentmodule. Codec pureJDK16JUnitPASS, syntheticsecret only; notproviderintegration. Commerce worker owns sharedMavenlease; loan explicitly before main reactor tests. No nativeDB/realpayment/push/commits.

## Files/interfaces

ExistingPaymentpom upgraded to same SpringMVC/JDBC/Flyway/Actuator/Postgres/gRPC/Testcontainers deps alreadyused inCommerce; identity-contract+commerce-contract only. Main ownsPaymentApplication/configuration/application.yaml, domain Payment/PaymentFailure, application IdentityAccess/PaymentOrders/PaymentProvider/PaymentStore/PaymentService, adapters HTTPcontrollers/security/errorfilter/JDBC/gRPC/VNPAYprovider queryHTTP, V1__payments.sql; unit/integration/API tests. No crossserviceDB or genericcommonlibrary.

PAYcodec API authenticate(rawQuery) returns sealed immutable authenticatedreference before DBlookup; reference.bind(expected) yields verifiedNotice or bindingerror. Merchant/HMAC check first, app looks up immutabletxnref second. Return.bindReturn onlycorrelation, nevercapture. Main appport wraps providerhandle, no rawquery/pan/OTP/secretpersisted. FixedHTTPSReturnrequired bycurrentcodec (localTLS/publicconfig gate later); no publictunnel silentlyopened.

Public5ops exact contract contracts/openapi/payment.v1.openapi.json. AttemptDTO currentstate PENDING/FAILED/EXPIRED/SUCCEEDED, originalid/order/requestkey/deadline, redirectnullable, application/refundnullable, reconciliationbool. New201/replay200, permanentactor/key hashboundoperation/order/body, strictbody{}, CSRF/currentauth before replay. GETbykey404isunknown and identity stays same. Link onlylivePENDING; neverregenerateclock/key onreplay. Expired localwait isnotfinancialfailure.

## Persistence/locks

AttemptUUID32hex isVNPAYtxnref; originalcreatedAt/expiry/ip/serverparams/link immutable. Unique(actor,key), uniqueTxnRef. Partialunique(orderId) for pending/expired unresolvedattempts; SUCCEEDED excluded frompartialindex so lateFAILED→SUCCEEDED cannot conflictwithnewPENDING andlosecapture. Creation underactoradvisorylock queriesanycapturedmoneyfororder anddeniesnewcharge. PriorverifiedFAILED permits explicitnewattempt; UNKNOWN/EXPIRED/07doesnot. Crossorderkeyreuse422.

Callback authenticator precedes read. Readattempt immutableprojection toknowactor, thenactoradvisorylock BEFORE attemptrowlock/recheck so no lockinversion withnewattempt. Recordcapturedtransactions by unique merchant+normalizednumericproviderTxnNo (strip leadingzeros fordedup), immutablecaptureUUID/order/actor/attempt/amount/currency. NewproviderTxnNo onalreadySUCCEEDEDattempt stillcreatesnewcapture/job; don'tdrop it asalreadyconfirmed. SameproviderTxndifferentbinding retainsfirstcapture +marksreconciliation, notsecondcredit.

Capture+ledgerCAPTURE+capturejob sameTX beforeIPNACK00. ExistingexactcaptureACK02. Signedfailure/unknown persisted withboundedtypedobservation/digest, no rawquery; no SUCCEEDED→FAILED fromlatercallback. Validcontradictory/mismatcheddata marksreconciliation andschedulesquery, nevererasesmoney. InvalidHMAC doesno lookup/write. NoSQL/secret/rawproviderdata inerrors/logs.

Capturejobs lease30s/revision/batch20/10s. Acquirecommit, networkCommerceApplyCapture outsideTX, completefenced. Samecapture/bodyonretryafterlostreply; notfoundnotrefundproof. APPLIED marksapplied; REFUND_REQUIRED createsonefullrefundliability+reservationledger; PROCESSING/unknownretainsjob. OrderPAIDonlyCommerceafterConsume, PaymentSUCCEEDEDonlyverifiedcapture; keepconceptsdistinct.

Refund liability UNIQUEcapture; reserveactualcaptureamount undercapturerowlock, ledgerdedup+budgetchecks. Onefullrefund/request identity persisted beforecall; pending+succeeded<=captured. Requests immutableID/date/fields acrossunknown; no newrequestID aftertimeout. APIaccepted/successHTTP notactualrefundproof. Query usingrefundTxnNo/type02 whenavailable; response type01/status00 isoriginalPAYsuccess, NOTrefundsuccess. No sufficientproviderbinding afterlostreply→RESERVED/NEEDS_RECONCILIATION visiblypending, neverfabricatedSUCCEEDED. Providerpermission errors safeoperatorreconciliation. FinancialrefundneverchangesCONSUMED/on_hand orterminalPAID.

## TDD sequence

1. PaymentApiTests existingrealPG/inprocessIdentityCommerce fixture validPOST→201/recoverbykey. Run actualREDmissingBootconfig, bootstrapapp/config, rerun expectedendpoint404 RED beforebusinessimplementation. No actualconfigureduserkeys inJUnit, spring.config.importempty andsyntheticproviderproperties.
2. Implement durableattempt+Identity/projectiongateway/HTTPminimum/V1 toGREEN. KeyreplaybeforeCommerce/config, ownership/currentCSRF, strictJSON/UUID/query/duplicatecookies/bytebounds. PricefromCommerceonly, noactiveDBconnectionduringRPC. SimultaneousfirstPOSTsamekey anddifferentkeysameorder enforceoneactiveattempt. Legacyunpayable409/typedunsupported.
3. SignedsyntheticIPN TDD verifiesbeforelookup, captures+ledger+jobcommittedbeforeACK. Testexactduplicate/signature/merchant/amount/owner/malformedstates/07unknown/latefailuredoesnoterasecapture, sameTxndifferentbindings, newTxnafterSUCCEEDEDstillrecorded. PaymentworkerApplylostresponse→samecapture eventuallyAPPLIED, notduplicatecash orrefund.
4. Providerquery/refund codecs useofficialpipeformat+constant-time signature/bindings; wholebodytimeout8sec/64KiBsize/staticallowlist, sanitizefailures. SyntheticHTTPfixture onlylabelledfixture. Query91/94/malformed/timeoutunknown, noautonewattempt. Refundresponse type01 cannotclaimrefunddone, lostreply preservesrequest/reserve.
5. Returnauthenticate/correlate/fixed303 canenqueueauthoritativequery; nevercapture/PAID. FirstqueryafterverifiedReturn avoidsunnecessaryincompletepoll; fallbackboundedqueryforunresolvedattempts. GETstatusno providernetworkinlocalTX. Providerpermissions/IPNHTTPS/tunnel and realbankportal steps main/usergate, notfixtureclaim.
6. Fullmodulegate/review, then mainrootverify/fresh5DBfixture/frontend+actualsandbox proof. Rootnotaccepteduntilallroutes/state/financialraces integrated. No autocommit. Ifproviderneedshumaninput/externalcallbackapproval askonlywhenconcreteready.

Defaultnewattempts disabled untilVNPAY_ENABLED=true; configuredkeys stillallowcallback/reconciliation foralreadycreatedattempts wheninitdisabled. Missingkeys failclosed503/retriableIPN99, not fake URL. Example/env docs no secrets. Workerinitialdelay configurable onlyfortestisolation; default10sec, test1hmanualrecover.
