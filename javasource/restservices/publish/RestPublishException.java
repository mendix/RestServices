package restservices.publish;

import org.apache.commons.httpclient.HttpStatus;

public class RestPublishException extends Exception {
	/**
	 * 
	 */
	private static final long serialVersionUID = -28082038806032565L;
	private RestExceptionType exType;

	public enum RestExceptionType { 
		NOT_FOUND(HttpStatus.SC_NOT_FOUND), 
		UNAUTHORIZED(HttpStatus.SC_UNAUTHORIZED),
		METHOD_NOT_ALLOWED(HttpStatus.SC_METHOD_NOT_ALLOWED),
		CONFLICTED(HttpStatus.SC_CONFLICT), 
		BAD_REQUEST(HttpStatus.SC_BAD_REQUEST);
		
		private int status;

		RestExceptionType(int status) {
			this.status = status;
		}
		
		public int getStatusCode() {
			return status;
		}
	}
	
	public RestPublishException(RestExceptionType exType, String message) {
		super(message);
		this.exType = exType;
	}
	
	public int getStatusCode() {
		return exType.getStatusCode();
	}
	
	public RestExceptionType getType() {
		return exType;
	}
}
