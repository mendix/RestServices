package restservices.publish;

import com.mendix.modules.webservices.WebserviceException;

public class CustomRestServiceException extends WebserviceException {

	/**
	 * 
	 */
	private static final long serialVersionUID = 3894071496070902197L;
	
	private int httpStatus;

	public CustomRestServiceException(String faultCode, String message, String detailMessage, int httpStatus) {
		super(faultCode, message);
		if (httpStatus < 400 || httpStatus >= 600)
			throw new IllegalArgumentException("HttpStatus should be between 400 and 599");
		this.setDetail(detailMessage);
		this.httpStatus = httpStatus;
	}

	public int getHttpStatus() {
		return this.httpStatus;
	}
}
