package restservices.publish;

import org.apache.http.HttpStatus;

public class RestRequestException extends Exception {
	/**
	 * 
	 */
	private static final long serialVersionUID = -28082038806032565L;
	private RestExceptionType exType;

	public enum RestExceptionType { 
		NOT_FOUND(HttpStatus.SC_NOT_FOUND), 
		UNAUTHORIZED(HttpStatus.SC_UNAUTHORIZED),
		METHOD_NOT_ALLOWED(HttpStatus.SC_METHOD_NOT_ALLOWED),
		CONFLICTED(HttpStatus.SC_CONFLICT);
		
		private int status;

		RestExceptionType(int status) {
			this.status = status;
		}
		
		public int getStatusCode() {
			return status;
		}
	}
	
	public RestRequestException(RestExceptionType exType, String message) {
		super(message);
		this.exType = exType;
	}
	
	public int getStatusCode() {
		return exType.getStatusCode();
	}
	
	
}
