package restservices;

import org.json.JSONObject;

import restservices.proxies.ObjectState;
import restservices.proxies.ServiceState;
import restservices.proxies.Subscriber;

import com.mendix.core.Core;
import com.mendix.core.CoreException;
import com.mendix.systemwideinterfaces.core.IContext;
import com.mendix.systemwideinterfaces.core.IMendixObject;

import communitycommons.XPath;

public class ChangeTracker {

	public void onAfterCommit(IContext context, IMendixObject source) throws Exception {
		if (source == null)
			return;
		
		PublishedService service = RestServices.getServiceForEntity(source.getType());
		
		//Check if publishable
		if (!service.identifierInConstraint(context, source.getId()))
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
		
		processUpdate(service, key, jsonString, eTag, false);
	}

	//TODO: move to service?
	private synchronized void processUpdate(PublishedService service,
			String key, String jsonString, String eTag, boolean deleted) throws Exception {

		IContext context = Core.createSystemContext();

		ServiceState serviceState = XPath.create(context, ServiceState.class).findOrCreate(ServiceState.MemberNames.Name, service.getName());
		
		ObjectState objectState = XPath.create(context, ObjectState.class)
				.eq(ObjectState.MemberNames.key, key)
				.eq(ObjectState.MemberNames.ObjectState_ServiceState, serviceState)
				.first();
		
		//not yet published
		if (objectState == null) {
			if (deleted) //no need to publish if it wasn't yet published
				return;
			
			objectState = new ObjectState(context);
			objectState.setkey(key);
			objectState.setObjectState_ServiceState(serviceState);
			storeUpdate(context, serviceState, objectState, eTag, jsonString, deleted);
		}
		
		else if (objectState != null && objectState.getetag().equals(eTag) && objectState.getdeleted() != deleted) 
			return; //nothing changed

		else
			storeUpdate(context, serviceState, objectState, eTag, jsonString, deleted);
	}

	private void storeUpdate(IContext context, ServiceState serviceState, ObjectState objectState,
			String eTag, String jsonString, boolean deleted) throws Exception {
		
		/* store the update*/
		long rev = serviceState.getRevision() + 1;
		
		objectState.setetag(eTag);
		objectState.setdeleted(deleted);
		objectState.setjson(jsonString);
		objectState.setrevision(rev);
		objectState.commit();
		
		serviceState.setRevision(rev);
		serviceState.commit();
		
		publishUpdate(serviceState, objectState, eTag, jsonString, deleted);
	}

	public void publishUpdate(ServiceState serviceState,
			ObjectState objectState, String eTag, String jsonString,
			boolean deleted) throws Exception {

		//TODO: do this async, separate thread etc etc
		for(Subscriber subscriber : XPath.create(Core.createSystemContext(), Subscriber.class).eq(Subscriber.MemberNames.Subscriber_ServiceState, serviceState).all()) {
			if (deleted)
				sendDeleteToSubscribers(serviceState, objectState.getkey());
			else
				sendUpdateToSubscribers(serviceState, objectState.getkey(), eTag, jsonString);
		}
	}

	private void sendUpdateToSubscribers(ServiceState serviceState,
			String key, String eTag, String jsonString) {
		// TODO Auto-generated method stub
		
	}

	private void sendDeleteToSubscribers(ServiceState serviceState,
			String key) {
		// TODO Auto-generated method stub
		
	}
}
