package restservices;

import org.json.JSONObject;


import com.mendix.systemwideinterfaces.core.IContext;
import com.mendix.systemwideinterfaces.core.IMendixObject;


public class ChangeTracker {

	public static void onAfterCommit(IContext context, IMendixObject source) throws Exception {
		onAfterCommit(context, source, true);
	}
	
	public static void onAfterCommit(IContext context, IMendixObject source, boolean checkConstraint) {
		if (source == null)
			return;
		
		PublishedService service = RestServices.getServiceForEntity(source.getType());
		
		try {
			//Check if publishable
			if (checkConstraint && !service.identifierInConstraint(context, source.getId()))
				return; 
			
			String key = service.getKey(context, source);
			if (!RestServices.isValidKey(key)) {
				RestServices.LOG.warn("No valid key for object " + source + "; skipping updates");
				return;
			}
				
			IMendixObject view = service.convertSourceToView(context, source);
			JSONObject result = PublishedService.convertViewToJson(context, view);
					
			String jsonString = result.toString(4);
			String eTag = PublishedService.getMD5Hash(jsonString);
			
			service.processUpdate(key, jsonString, eTag, false);
		}
		catch(Exception e) {
			throw new RuntimeException("Failed to process change for " + source + ": " + e.getMessage(), e);
		}
	}
}
