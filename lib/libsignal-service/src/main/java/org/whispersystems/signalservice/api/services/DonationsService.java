package org.whispersystems.signalservice.api.services;

import org.signal.core.util.logging.Log;
import org.signal.libsignal.zkgroup.receipts.ReceiptCredentialPresentation;
import org.signal.libsignal.zkgroup.receipts.ReceiptCredentialRequest;
import org.signal.libsignal.zkgroup.receipts.ReceiptCredentialResponse;
import org.signal.network.rest.RequestResultExtensionsKt;
import org.whispersystems.signalservice.api.NetworkResultUtil;
import org.whispersystems.signalservice.api.donations.DonationPermitProvider;
import org.whispersystems.signalservice.api.donations.DonationsApi;
import org.signal.network.exceptions.NonSuccessfulResponseCodeException;
import org.whispersystems.signalservice.api.subscriptions.ActiveSubscription;
import org.whispersystems.signalservice.api.subscriptions.SubscriberId;
import org.whispersystems.signalservice.internal.EmptyResponse;
import org.whispersystems.signalservice.internal.ServiceResponse;
import org.whispersystems.signalservice.internal.push.SubscriptionsConfiguration;

import java.io.IOException;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;

import kotlin.Pair;

import io.reactivex.rxjava3.annotations.NonNull;

/**
 * One-stop shop for Signal service calls related to donations.
 */
public class DonationsService {

  private static final long DONATION_CONFIGURATION_TTL = TimeUnit.HOURS.toMillis(1);

  private static final String TAG = DonationsService.class.getSimpleName();

  private final DonationsApi           donationsApi;
  private final DonationPermitProvider donationPermitProvider;

  private final AtomicReference<CacheEntry<SubscriptionsConfiguration>> donationsConfigurationCache = new AtomicReference<>(null);

  private static class CacheEntry<T> {
    private final T      cachedValue;
    private final long   expiresAt;
    private final Locale locale;

    private CacheEntry(T cachedValue, long expiresAt, Locale locale) {
      this.cachedValue = cachedValue;
      this.expiresAt   = expiresAt;
      this.locale      = locale;
    }
  }

  public DonationsService(@NonNull DonationsApi donationsApi, @NonNull DonationPermitProvider donationPermitProvider) {
    this.donationsApi           = donationsApi;
    this.donationPermitProvider = donationPermitProvider;
  }

  /**
   * Allows a user to redeem a given receipt they were given after submitting a donation successfully.
   *
   * @param receiptCredentialPresentation Receipt
   * @param visible                       Whether the badge will be visible on the user's profile immediately after redemption
   * @param primary                       Whether the badge will be made primary immediately after redemption
   */
  public ServiceResponse<EmptyResponse> redeemDonationReceipt(ReceiptCredentialPresentation receiptCredentialPresentation, boolean visible, boolean primary) {
    try {
      NetworkResultUtil.successOrThrow(donationsApi.redeemDonationReceipt(receiptCredentialPresentation, visible, primary));
      return ServiceResponse.forResult(EmptyResponse.INSTANCE, 200, null);
    } catch (Exception e) {
      return ServiceResponse.<EmptyResponse>forUnknownError(e);
    }
  }

  /**
   * Allows a user to redeem a given receipt they were given after submitting a donation successfully.
   *
   * @param receiptCredentialPresentation Receipt
   */
  public ServiceResponse<EmptyResponse> redeemArchivesReceipt(ReceiptCredentialPresentation receiptCredentialPresentation) {
    try {
      NetworkResultUtil.successOrThrow(donationsApi.redeemArchivesReceipt(receiptCredentialPresentation));
      return ServiceResponse.forResult(EmptyResponse.INSTANCE, 200, null);
    } catch (Exception e) {
      return ServiceResponse.<EmptyResponse>forUnknownError(e);
    }
  }

  public ServiceResponse<SubscriptionsConfiguration> getDonationsConfiguration(Locale locale) {
    return getCachedValue(
        locale,
        donationsConfigurationCache,
        l -> NetworkResultUtil.successOrThrow(donationsApi.getDonationsConfiguration(l)),
        DONATION_CONFIGURATION_TTL
    );
  }

  private <T> ServiceResponse<T> getCachedValue(Locale locale,
                                                AtomicReference<CacheEntry<T>> cachedValueReference,
                                                CacheEntryValueProducer<T> cacheEntryValueProducer,
                                                long cacheTTL
  )
  {
    CacheEntry<T> cacheEntryOutsideLock = cachedValueReference.get();
    if (isNewCacheEntryRequired(cacheEntryOutsideLock, locale)) {
      synchronized (this) {
        CacheEntry<T> cacheEntryInLock = cachedValueReference.get();
        if (isNewCacheEntryRequired(cacheEntryInLock, locale)) {
          return wrapInServiceResponse(() -> {
            T value = cacheEntryValueProducer.produce(locale);
            cachedValueReference.set(new CacheEntry<>(value, System.currentTimeMillis() + cacheTTL, locale));
            return new Pair<>(value, 200);
          });
        } else {
          return wrapInServiceResponse(() -> new Pair<>(cacheEntryInLock.cachedValue, 200));
        }
      }
    } else {
      return wrapInServiceResponse(() -> new Pair<>(cacheEntryOutsideLock.cachedValue, 200));
    }
  }

  public ServiceResponse<EmptyResponse> linkGooglePlayBillingPurchaseTokenToSubscriberId(SubscriberId subscriberId, String purchaseToken, Lock lock) {
    return wrapInServiceResponse(() -> {
      lock.lock();
      try {
        NetworkResultUtil.successOrThrow(donationsApi.linkPlayBillingPurchaseToken(subscriberId, purchaseToken));
      } finally {
        lock.unlock();
      }

      return new Pair<>(EmptyResponse.INSTANCE, 200);
    });
  }

  /**
   * Synchronously returns information about the current subscription if one exists.
   */
  public ServiceResponse<ActiveSubscription> getSubscription(SubscriberId subscriberId) {
    return wrapInServiceResponse(() -> {
      ActiveSubscription response = NetworkResultUtil.successOrThrow(donationsApi.getSubscription(subscriberId));
      return new Pair<>(response, 200);
    });
  }

  /**
   * Refreshes an existing subscriber's access time (keep-alive). Does not attach a donation permit; use {@link #createSubscriber} for a new subscriber.
   * <p>
   * Clients MUST periodically hit this endpoint to update the access time on the subscription record. Recommend trying to call it approximately
   * every 3 days. Not accessing this endpoint for an extended period of time will result in the subscription being canceled.
   *
   * @param subscriberId The subscriber ID for the user polling their subscription
   */
  public ServiceResponse<EmptyResponse> putSubscription(SubscriberId subscriberId) {
    return wrapInServiceResponse(() -> {
      NetworkResultUtil.successOrThrow(donationsApi.putSubscription(subscriberId));
      return new Pair<>(EmptyResponse.INSTANCE, 200);
    });
  }

  /**
   * Creates a subscriber record on the signal server and stripe, attaching a single-use donation permit. Only use when the subscriber does not
   * yet exist. After receiving 200 from this endpoint, clients should save subscriberId locally and to storage service for the account. If you
   * get a 403 from this endpoint and you did not use an account authenticated connection, then the subscriberId has been corrupted in some way.
   *
   * @param subscriberId The subscriber ID for the newly-created subscriber
   */
  public ServiceResponse<EmptyResponse> createSubscriber(SubscriberId subscriberId) {
    return wrapInServiceResponse(() -> {
      String donationPermit = RequestResultExtensionsKt.successOrThrow(donationPermitProvider.getDonationPermit());
      NetworkResultUtil.successOrThrow(donationsApi.createSubscriber(subscriberId, donationPermit));
      return new Pair<>(EmptyResponse.INSTANCE, 200);
    });
  }

  /**
   * Cancels any current subscription at the end of the current subscription period.
   *
   * @param subscriberId The subscriber ID for the user cancelling their subscription
   */
  public ServiceResponse<EmptyResponse> cancelSubscription(SubscriberId subscriberId) {
    return wrapInServiceResponse(() -> {
      NetworkResultUtil.successOrThrow(donationsApi.deleteSubscription(subscriberId));
      return new Pair<>(EmptyResponse.INSTANCE, 200);
    });
  }

  public ServiceResponse<ReceiptCredentialResponse> submitReceiptCredentialRequestSync(SubscriberId subscriberId, ReceiptCredentialRequest receiptCredentialRequest) {
    return wrapInServiceResponse(() -> {
      ReceiptCredentialResponse response = NetworkResultUtil.successOrThrow(donationsApi.submitReceiptCredentials(subscriberId, receiptCredentialRequest));
      return new Pair<>(response, 200);
    });
  }

  private <T> ServiceResponse<T> wrapInServiceResponse(Producer<T> producer) {
    try {
      Pair<T, Integer> responseAndCode = producer.produce();
      return ServiceResponse.forResult(responseAndCode.getFirst(), responseAndCode.getSecond(), null);
    } catch (NonSuccessfulResponseCodeException e) {
      Log.w(TAG, "Bad response code from server.", e);
      return ServiceResponse.forApplicationError(e, e.code, e.getStringBody());
    } catch (IOException e) {
      Log.w(TAG, "An unknown error occurred.", e);
      return ServiceResponse.forUnknownError(e);
    }
  }

  private <T> boolean isNewCacheEntryRequired(CacheEntry<T> cacheEntry, Locale locale) {
    return cacheEntry == null || cacheEntry.expiresAt < System.currentTimeMillis() || !Objects.equals(locale, cacheEntry.locale);
  }

  private interface Producer<T> {
    Pair<T, Integer> produce() throws IOException;
  }

  interface CacheEntryValueProducer<T> {
    T produce(Locale locale) throws IOException;
  }
}
