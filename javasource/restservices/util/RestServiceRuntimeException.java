package restservices.util;

public class RestServiceRuntimeException extends RuntimeException {

	/**
	 * 
	 */
	private static final long serialVersionUID = 3403872096764946199L;

	public RestServiceRuntimeException(String url) {
		super(url);
	}

	public RestServiceRuntimeException(Throwable throwable) {
		super(throwable);
	}

	public RestServiceRuntimeException(String msg, Throwable throwable) {
		super(msg, throwable);
	}

}
