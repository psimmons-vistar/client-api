package com.vistarmedia.api;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.vistarmedia.api.future.ApiResultFuture;
import com.vistarmedia.api.message.Api.AdRequest;
import com.vistarmedia.api.message.Api.AdResponse;
import com.vistarmedia.api.message.Api.ProofOfPlay;
import com.vistarmedia.api.result.AdResponseResult;
import com.vistarmedia.api.result.ErrorResult;
import com.vistarmedia.api.result.ProofOfPlayResult;
import com.vistarmedia.api.transport.Transport;
import com.vistarmedia.api.transport.TransportResponseHandler;

/**
 * <p>
 * Simple client for interacting with Vistar Media's API servers. This client,
 * by default is asynchronous to help bulk loading speeds. However, depending on
 * the implementing transport, the calls may appear to be asynchronous but will
 * execute serially under the hood. It is recommended to use an
 * <code>ApiClient</code> with an
 * {@link com.vistarmedia.api.transport.AsyncHttpClientTransport
 * AsyncHttpClientTransport}.
 * </p>
 * 
 * <h4>Instantiating</h4>
 * <p>
 * Rather than creating an {@code ApiClient} by hand, it is often easier to ask
 * one of the provided factories to make one for you. This will insure that
 * {@link com.vistarmedia.api.transport.Transport} is initialized properly.
 * </p>
 * 
 * <pre class="pretty">
 * ApiClient client = {@link com.vistarmedia.api.transport.AsyncHttpClientTransport}.connect("dev.api.vistarmedia.com", 80);
 * </pre>
 * 
 * <p>
 * The {@code ApiClient} instances are safe to share across threads.
 * Instantiating multiple instances is safe to do, it will simply waste
 * resources.
 * </p>
 * 
 * <h4>Sending Ad Requests</h4>
 * <p>
 * There are two ways to send {@link com.vistarmedia.message.Api.AdRequest}s.
 * The simpler of the two is synchronously. However, it is far more inefficient.
 * Better throughput can be achieved by sending asynchronous requests to Vistar
 * Media's API server, it may lead to more complex code however.
 * </p>
 * 
 * <p>
 * Examples of the two styles are outlined below.
 * </p>
 * 
 * <h4>Synchronous Ad Requests</h4>
 * <p>
 * In the synchronous model, requests will be sent serially to Vistar Media's ad
 * server. Exceptions will be thrown directly at request time. There is also a
 * hard timeout of 10 seconds for any ad server communication. Requests
 * exceeding this window will be aborted and an exception will be raised.
 * </p>
 * 
 * <h4>Asynchronous Ad Requests</h4>
 * <p>
 * </p>
 * 
 * <h4>Common HTTP Error Codes</h4>
 * 
 */
public class ApiClient {

  private String              host;
  private int                 port;
  private Transport           transport;
  private int                 syncTimeoutSeconds;

  private static final String GET_AD_PATH        = "/api/v1/get_ad/protobuf";
  private static final String PROOF_OF_PLAY_PATH = "/api/v1/proof_of_play/protobuf";

  public ApiClient(String host, int port, Transport transport,
      int syncTimeoutSeconds) {
    this.host = host;
    this.port = port;
    this.transport = transport;
    this.syncTimeoutSeconds = syncTimeoutSeconds;
  }

  /**
   * <p>
   * Instantiate an ApiClient directly. A transport's factory method should
   * instead be used, but this method of creation will work.
   * </p>
   * 
   * <p>
   * This will configure the client to time out after 10 seconds for synchronous
   * requests.
   * </p>
   * 
   * @param host
   *          Vistar Media API host name, ie: {@code dev.api.vistarmedia.com}
   * @param port
   *          Port to connect to the Vistar Media server, nearly always 80
   * @param transport
   *          Transport implementation to send requests over.
   */
  public ApiClient(String host, int port, Transport transport) {
    this(host, port, transport, 10);
  }

  /**
   * Asynchronously send an {@link com.vistarmedia.api.message.Api.AdRequest}
   * over the configured transport to the Vistar Media API server. This will
   * return a result future which will be filled at some point in the
   * background. The {@link com.vistarmedia.api.result.AdResponseResult} may
   * contain either an {@link com.vistarmedia.api.message.Api.AdResponse} or an
   * {@link com.vistarmedia.api.result.ErrorResult} describing what went wrong
   * during the operation.
   * 
   * @param request
   *          Request that will be asynchronously sent to the Vistar Media API
   *          servers
   * @return A Future containing the eventual result of this operation, be it an
   *         error or success.
   */
  public Future<AdResponseResult> sendAdRequest(AdRequest request) {
    final ApiResultFuture<AdResponseResult> result = new ApiResultFuture<AdResponseResult>();
    TransportResponseHandler handler = new TransportResponseHandler() {

      @Override
      public void onThrowable(Throwable t) {
        onError(400, t.getLocalizedMessage());
      }

      @Override
      public void onResponse(int code, String message, InputStream body) {
        if (code == 200) {
          try {
            AdResponse response = AdResponse.parseFrom(body);
            result.fulfill(new AdResponseResult(response));
          } catch (IOException e) {
            onError(500, e.getLocalizedMessage());
          }

        } else {
          onError(code, message);
        }
      }

      private void onError(int code, String message) {
        ErrorResult error = new ErrorResult(code, message);
        result.fulfill(new AdResponseResult(error));
      }
    };

    sendRequest(GET_AD_PATH, request.toByteArray(), handler);
    return result;
  }

  /**
   * <p>
   * Synchronously get an {@code AdResponse}. This will respond within the
   * default timeout (10 seconds) or throw an exception. If there is any problem
   * with the request, an {@link com.vistarmedia.api.ApiRequestException} will
   * be thrown with the HTTP code and a string describing the problem.
   * </p>
   * 
   * @param request
   * @return A valid AdResponse from the Vistar Media API servers.
   * @throws ApiRequestException
   *           thrown when there was any problem with the request.
   */
  public AdResponse getAdResponse(AdRequest request) throws ApiRequestException {
    Future<AdResponseResult> resultFuture = sendAdRequest(request);
    AdResponseResult result;
    try {
      result = resultFuture.get(syncTimeoutSeconds, TimeUnit.SECONDS);
    } catch (Throwable t) {
      throw new ApiRequestException(408, t.getLocalizedMessage());
    }

    if (result == null) {
      throw new ApiRequestException(408, "Request Timeout");
    }

    if (result.isSuccess()) {
      return result.getResult();
    } else {
      ErrorResult error = result.getError();
      throw new ApiRequestException(error.getCode(), error.getMessage());
    }
  }

  /**
   * <p>
   * Synchronously sends a proof of play to Vistar Media's API servers. Idential
   * to {@code #getAdResponse(AdRequest)}, this will raise an
   * {@code ApiRequestException} after a specified timeout.
   * {@code ApiRequestException}s will also be raised for any response errors.
   * See the table at the top for more information about the different response
   * codes.
   * </p>
   * 
   * @param request
   *          Proof of play to register
   * @return The same Proof of play echoed back from Vistar's servers
   * @throws ApiRequestException
   *           thrown whenever this is either a timeout or some manner of
   *           invalid request/response.
   */
  public ProofOfPlay getProofOfPlay(ProofOfPlay request)
      throws ApiRequestException {
    Future<ProofOfPlayResult> resultFuture = sendProofOfPlay(request);
    ProofOfPlayResult result;
    try {
      result = resultFuture.get(syncTimeoutSeconds, TimeUnit.SECONDS);
    } catch (Throwable t) {
      throw new ApiRequestException(408, t.getLocalizedMessage());
    }

    if (result == null) {
      throw new ApiRequestException(408, "Request Timeout");
    }

    if (result.isSuccess()) {
      return result.getResult();
    } else {
      ErrorResult error = result.getError();
      throw new ApiRequestException(error.getCode(), error.getMessage());
    }
  }

  /**
   * <p>
   * Asynchronously send an {@link com.vistarmedia.api.message.Api.ProofOfPlay}
   * over the configured transport to the Vistar Media API server. This will
   * return a result future which will be filled at some point in the
   * background. The {@link com.vistarmedia.api.result.ProofOfPlayResult} may
   * contain either an {@link com.vistarmedia.api.message.Api.ProofOfPlay} or an
   * {@link com.vistarmedia.api.result.ErrorResult} describing what went wrong
   * during the operation.
   * </p>
   * 
   * <p>
   * This request should be sent after an asset has been shown for at least the
   * time specified in the corresponding AdResonse's Advertisement.
   * </p>
   * 
   * <p>
   * The server will response back with an identical proof of play that has been
   * sent. In the future, this may also attach pricing information for internal
   * reporting.
   * </p>
   * 
   * @param request
   *          Proof of play describing when and for how long the advertisment
   *          was shown on the player.
   * @return A Future containing the eventual result of this operation, be it an
   *         error or success.
   */
  public Future<ProofOfPlayResult> sendProofOfPlay(ProofOfPlay request) {
    final ApiResultFuture<ProofOfPlayResult> result = new ApiResultFuture<ProofOfPlayResult>();
    TransportResponseHandler handler = new TransportResponseHandler() {
      @Override
      public void onThrowable(Throwable t) {
        onError(400, t.getLocalizedMessage());
      }

      @Override
      public void onResponse(int code, String message, InputStream body) {
        if (code == 200) {
          try {
            ProofOfPlay response = ProofOfPlay.parseFrom(body);
            result.fulfill(new ProofOfPlayResult(response));
          } catch (IOException e) {
            onError(500, e.getLocalizedMessage());
          }

        } else {
          onError(code, message);
        }
      }

      private void onError(int code, String message) {
        ErrorResult error = new ErrorResult(code, message);
        result.fulfill(new ProofOfPlayResult(error));
      }
    };

    sendRequest(PROOF_OF_PLAY_PATH, request.toByteArray(), handler);
    return result;
  }

  /**
   * <p>
   * Implementation to have the {@code Transport} start handling a request at
   * the given path (relative to the provided host/port fields) and body. This
   * will simply kick off the request, no provide any result. Results are
   * indirectly routed to the handler
   * </p>
   * 
   * @param path
   *          Relative path of the request
   * @param body
   *          {@code POST} body to send to the Vistar Media API server
   * @param handler
   *          Handler which will be called whenever there is a successful
   *          response or error.
   */
  private void sendRequest(String path, byte[] body,
      TransportResponseHandler handler) {

    URL url;
    try {
      url = createUrl(path);
    } catch (MalformedURLException e) {
      handler.onThrowable(e);
      return;
    }

    try {
      transport.post(url, body, handler);
    } catch (IOException e) {
      handler.onThrowable(e);
    }
  }

  /**
   * <p>
   * Convert a relative path to an absolute URL given the provided {@code host}
   * and {@code port} fields. Unless instantiated with a completely invalid
   * hostname, this should not throw errors.
   * </p>
   * 
   * @param path
   *          relative path for the generated {@code URL}.
   * @return Absolute {@code URL} based on the {@code host} and {@code port}
   *         fields with the given relative {@code path}
   * @throws MalformedURLException
   *           Thrown in the case that the resultign {@code URL} is not valid.
   *           This should only happen if the {@code ApiClient} has been
   *           instantiated with a host name that is completely invalid (ie:
   *           containing a colon or a slash).
   */
  private URL createUrl(String path) throws MalformedURLException {
    String urlString = String.format("http://%s:%s%s", host, port, path);
    return new URL(urlString);
  }
}