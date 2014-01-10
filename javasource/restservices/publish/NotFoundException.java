package restservices.publish;

public class NotFoundException extends RuntimeException {

	/**
	 * 
	 */
	private static final long serialVersionUID = -5093618159743065630L;

	public NotFoundException(String message) {
		super(message);
	}
}
