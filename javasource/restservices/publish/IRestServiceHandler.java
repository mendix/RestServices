package restservices.publish;

import java.util.Map;

public interface IRestServiceHandler {
	public void execute(RestServiceRequest rsr, Map<String, String> params) throws Exception;
}
