package restservices;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpException;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.http.HttpResponse;
import org.codehaus.jackson.annotate.JsonProperty;

public class ConsumedService {
	@JsonProperty
	private String serviceurl;
	
	HttpClient client;
	
	public ConsumedService() {
		client = new HttpClient();//TODO: not thread safe!
	}
	
	void getUrl(String relpath) throws HttpException, IOException {
		GetMethod get = new GetMethod(serviceurl + (relpath != null ? "/" + relpath : ""));
		get.setRequestHeader(Constants.ACCEPT_HEADER, Constants.TEXTJSON);
		HttpResponse response = client.execute(get);

		// Get the response
		BufferedReader rd = new BufferedReader
		  (new InputStreamReader(response.getEntity().getContent()));
		    
		String line = "";
		while ((line = rd.readLine()) != null) {
		 // textView.append(line);
		} 
		
		get.releaseConnection();
	}
}
