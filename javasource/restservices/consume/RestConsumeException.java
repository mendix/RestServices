package restservices.consume;

import restservices.consume.RestConsumer.HttpResponseData;

public class RestConsumeException extends Exception {

	/**
	 * 
	 */
	private static final long serialVersionUID = 6578655565304975962L;
	
	private final HttpResponseData responseData;
	private final int status;

	public RestConsumeException(HttpResponseData response) {
		super(response.getStatus() + ". Rest request failed: " + response.toString());
		this.responseData = response;
		this.status = response.getStatus();
	}
	
	public RestConsumeException(int status, String message) {
		super(status + " status was unexpected. " + message);
		this.status = status;
		this.responseData = null;
	}
	
	public int getStatus() {
		return status;
	}
	
	public HttpResponseData getResponseData() {
		return responseData;
	}
	
	public boolean hasResponseData() {
		return responseData != null;
	}

}
