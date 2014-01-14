package restservices.publish;

public class RestRequestException extends Exception {
	/**
	 * 
	 */
	private static final long serialVersionUID = -28082038806032565L;
	private int status;

	public RestRequestException(int status, String message) {
		super(message);
		this.status = status;
	}
	
	public int getStatus() {
		return status;
	}
	
	
}
