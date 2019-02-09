package org.whispersystems.textsecuregcm.push;

import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.SharedMetricRegistries;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.whispersystems.gcm.server.Message;
import org.whispersystems.gcm.server.Result;
import org.whispersystems.gcm.server.Sender;
import org.whispersystems.textsecuregcm.configuration.GcmConfiguration;
import org.whispersystems.textsecuregcm.storage.Account;
import org.whispersystems.textsecuregcm.storage.AccountsManager;
import org.whispersystems.textsecuregcm.storage.Device;
import org.whispersystems.textsecuregcm.util.Constants;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.codahale.metrics.MetricRegistry.name;
import io.dropwizard.lifecycle.Managed;

public class GCMSender implements Managed {

  private final Logger logger = LoggerFactory.getLogger(GCMSender.class);

  private final MetricRegistry metricRegistry = SharedMetricRegistries.getOrCreate(Constants.METRICS_NAME);
  private final Meter          success        = metricRegistry.meter(name(getClass(), "sent", "success"));
  private final Meter          failure        = metricRegistry.meter(name(getClass(), "sent", "failure"));
  private final Meter          unregistered   = metricRegistry.meter(name(getClass(), "sent", "unregistered"));
  private final Meter          canonical      = metricRegistry.meter(name(getClass(), "sent", "canonical"));

  private final Map<String, Meter> outboundMeters = new HashMap<String, Meter>() {{
    put("receipt", metricRegistry.meter(name(getClass(), "outbound", "receipt")));
    put("notification", metricRegistry.meter(name(getClass(), "outbound", "notification")));
  }};


  private final AccountsManager   accountsManager;
  private final Sender            signalSender;
  private       ExecutorService   executor;

  public GCMSender(AccountsManager accountsManager, GcmConfiguration configuration) {
    if (configuration == null ||
        configuration.getApiKey() == null) {
        logger.warn("Google Cloud Messaging (GCM) Unconfigured - Android and Web wakeup will not work");
        this.accountsManager = null;
        this.signalSender = null;
        return;
    }
    this.accountsManager = accountsManager;
    this.signalSender    = new Sender(configuration.getApiKey(), 50);
  }

  @VisibleForTesting
  public GCMSender(AccountsManager accountsManager, Sender sender, ExecutorService executor) {
    this.accountsManager = accountsManager;
    this.signalSender    = sender;
    this.executor        = executor;
  }

  public void sendMessage(GcmMessage message) {
    if (this.signalSender == null) {
        return;
    }
    Message.Builder builder = Message.newBuilder()
                                     .withDestination(message.getGcmId())
                                     .withPriority("high");

    String  key     = message.isReceipt() ? "receipt" : "notification";
    Message request = builder.withDataPart(key, "").build();

    ListenableFuture<Result> future = signalSender.send(request, message);
    markOutboundMeter(key);

    Futures.addCallback(future, new FutureCallback<Result>() {
      @Override
      public void onSuccess(Result result) {
        if (result.isUnregistered() || result.isInvalidRegistrationId()) {
          handleBadRegistration(result);
        } else if (result.hasCanonicalRegistrationId()) {
          handleCanonicalRegistrationId(result);
        } else if (!result.isSuccess()) {
          handleGenericError(result);
        } else {
          success.mark();
        }
      }

      @Override
      public void onFailure(Throwable throwable) {
        logger.warn("GCM Failed: " + throwable);
      }
    }, executor);
  }

  @Override
  public void start() {
    if (this.signalSender == null) {
        return;
    }
    executor = Executors.newSingleThreadExecutor();
  }

  @Override
  public void stop() throws IOException {
    if (this.signalSender == null) {
        return;
    }
    this.signalSender.stop();
    this.executor.shutdown();
  }

  private void handleBadRegistration(Result result) {
    GcmMessage message = (GcmMessage)result.getContext();
    logger.info("Got GCM unregistered notice: " + message.getNumber() + "." + message.getDeviceId());

    Optional<Account> account = getAccountForEvent(message);

    if (account.isPresent()) {
      Device device = account.get().getDevice(message.getDeviceId()).get();
      device.setGcmId(null);

      accountsManager.update(account.get());
    }

    unregistered.mark();
  }

  private void handleCanonicalRegistrationId(Result result) {
    GcmMessage message = (GcmMessage)result.getContext();
    logger.warn(String.format("Actually received 'CanonicalRegistrationId' ::: (canonical=%s), (original=%s)",
                              result.getCanonicalRegistrationId(), message.getGcmId()));

    Optional<Account> account = getAccountForEvent(message);

    if (account.isPresent()) {
      Device device = account.get().getDevice(message.getDeviceId()).get();
      device.setGcmId(result.getCanonicalRegistrationId());

      accountsManager.update(account.get());
    }

    canonical.mark();
  }

  private void handleGenericError(Result result) {
    GcmMessage message = (GcmMessage)result.getContext();
    logger.warn(String.format("Unrecoverable Error ::: (error=%s), (gcm_id=%s), " +
                              "(destination=%s), (device_id=%d)",
                              result.getError(), message.getGcmId(), message.getNumber(),
                              message.getDeviceId()));
    failure.mark();
  }

  private Optional<Account> getAccountForEvent(GcmMessage message) {
    Optional<Account> account = accountsManager.get(message.getNumber());

    if (account.isPresent()) {
      Optional<Device> device = account.get().getDevice(message.getDeviceId());

      if (device.isPresent()) {
        if (message.getGcmId().equals(device.get().getGcmId())) {
          logger.info("GCM Unregister GCM ID matches!");

          if (device.get().getPushTimestamp() == 0 || System.currentTimeMillis() > (device.get().getPushTimestamp() + TimeUnit.SECONDS.toMillis(10)))
          {
            logger.info("GCM Unregister Timestamp matches!");

            return account;
          }
        }
      }
    }

    return Optional.absent();
  }

  private void markOutboundMeter(String key) {
    Meter meter = outboundMeters.get(key);

    if (meter != null) meter.mark();
    else               logger.warn("Unknown outbound key: " + key);
  }
}
