package org.thoughtcrime.securesms.net;

import androidx.annotation.NonNull;

import org.whispersystems.signalservice.internal.util.BlacklistingTrustManager;

import java.net.InetAddress;
import java.net.ProxySelector;
import java.net.UnknownHostException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

import javax.net.SocketFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import okhttp3.Dns;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.dnsoverhttps.DnsOverHttps;

public class DohClient implements Dns {

  private final DnsOverHttps client;

  public DohClient(@NonNull String url, SocketFactory socketFactory, ProxySelector proxySelector,
                   Consumer<OkHttpClient.Builder> configure, UnaryOperator<SSLSocketFactory> secureSocketFactory) {
    this.client = new DnsOverHttps.Builder()
                                  .url(HttpUrl.get(url))
                                  .client(buildHttpClient(socketFactory, proxySelector, configure, secureSocketFactory))
                                  .includeIPv6(false)
                                  .build();
  }

  @Override
  public @NonNull List<InetAddress> lookup(@NonNull String hostname) throws UnknownHostException {
    return client.lookup(hostname);
  }

  static private OkHttpClient buildHttpClient(SocketFactory socketFactory, ProxySelector proxySelector,
                                               Consumer<OkHttpClient.Builder> configure, UnaryOperator<SSLSocketFactory> secureSocketFactory) {
    try {
      X509TrustManager trustManager = BlacklistingTrustManager.createForDefaultTrustStore();
      SSLContext       sslContext   = SSLContext.getInstance("TLS");

      sslContext.init(null, new TrustManager[] { trustManager }, null);

      OkHttpClient.Builder builder = new OkHttpClient.Builder()
                             .sslSocketFactory(secureSocketFactory.apply(sslContext.getSocketFactory()), trustManager)
                             .socketFactory(socketFactory)
                             .proxySelector(proxySelector)
                             .cache(null);
      configure.accept(builder);
      return builder.build();
    } catch (NoSuchAlgorithmException | KeyManagementException e) {
      throw new AssertionError(e);
    }
  }
}
