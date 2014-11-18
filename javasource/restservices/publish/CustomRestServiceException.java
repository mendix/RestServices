package restservices.publish;

import com.mendix.integration.WebserviceException;

public class CustomRestServiceException extends WebserviceException {

	/**
	 * 
	 */
	private static final long serialVersionUID = 3894071496070902197L;
	
	private int httpStatus;

	public CustomRestServiceException(String errorCode, String error, int httpStatus) {
		super(httpStatus < 500 ? WebserviceException.clientFaultCode : WebserviceException.serverFaultCode, error);
		if (httpStatus < 400 || httpStatus >= 600)
			throw new IllegalArgumentException("HttpStatus should be between 400 and 599");

		this.setDetail(errorCode);
		this.httpStatus = httpStatus;
	}

	public int getHttpStatus() {
		return this.httpStatus;
	}
}
