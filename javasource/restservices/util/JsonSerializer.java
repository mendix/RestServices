package restservices.util;

import java.util.Date;
import java.util.List;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONObject;

import restservices.RestServices;
import restservices.publish.PublishedService;

import com.mendix.core.Core;
import com.mendix.core.objectmanagement.member.MendixObjectReference;
import com.mendix.core.objectmanagement.member.MendixObjectReferenceSet;
import com.mendix.systemwideinterfaces.core.IContext;
import com.mendix.systemwideinterfaces.core.IMendixIdentifier;
import com.mendix.systemwideinterfaces.core.IMendixObject;
import com.mendix.systemwideinterfaces.core.IMendixObjectMember;
import com.mendix.systemwideinterfaces.core.meta.IMetaObject;

public class JsonSerializer {

	/**
	 * returns a json string containingURL if id is persistable or json object if with the json representation if the object is not. s
	 * @param rsr
	 * @param id
	 * @return
	 * @throws Exception 
	 */
	public static Object identifierToJSON(IContext context, IMendixIdentifier id) throws Exception {
		if (id == null)
			return null;
		
		/* persistable object, generate url */
		if (Core.getMetaObject(id.getObjectType()).isPersistable()) {
		
			PublishedService service = RestServices.getServiceForEntity(id.getObjectType());
			if (service == null) {
				RestServices.LOG.warn("No RestService has been definied for type: " + id.getObjectType() + ", identifier could not be serialized");
				return null;
			}
		
			if (service.identifierInConstraint(context, id)) {
				IMendixObject obj = Core.retrieveId(context, id); //TODO: inefficient, especially for refsets, use retrieveIds?
				if (obj == null) {
					RestServices.LOG.warn("Failed to retrieve identifier: " + id + ", does the object still exist?");
					return null;
				}
				return service.getObjecturl(context, obj);
			}
			return null;
		}
		
		/* transient object, export */
		else {
			IMendixObject obj = Core.retrieveId(context, id); //TODO: inefficient, especially for refsets, use retrieveIds?
			if (obj == null) {
				RestServices.LOG.warn("Failed to retrieve identifier: " + id + ", does the object still exist?");
				return null;
			}
			return convertMendixObjectToJson(context, obj);
		}
	}

	//TODO: move to separate class?
	public static JSONObject convertMendixObjectToJson(IContext context, IMendixObject view) throws Exception {
		JSONObject res = new JSONObject();
		
		Map<String, ? extends IMendixObjectMember<?>> members = view.getMembers(context);
		for(java.util.Map.Entry<String, ? extends IMendixObjectMember<?>> e : members.entrySet())
			serializeMember(context, res, e.getValue(), view.getMetaObject());
		
		return res;
	}

	public static void serializeMember(IContext context, JSONObject target,
			IMendixObjectMember<?> member, IMetaObject viewType) throws Exception {
		if (context == null)
			throw new IllegalStateException("Context is null");
	
		Object value = member.getValue(context);
		String memberName = member.getName();
		
		//Primitive?
		if (!(member instanceof MendixObjectReference) && !(member instanceof MendixObjectReferenceSet)) {
			switch(viewType.getMetaPrimitive(member.getName()).getType()) {
			case AutoNumber:
			case Long:
			case Boolean:
			case Currency:
			case Float:
			case Integer:
				//Numbers or bools should never be null!
				if (value == null)
					throw new IllegalStateException("Primitive member " + member.getName() + " should not be null!");
	
				target.put(memberName, value);
				break;
			case Enum:
			case HashString:
			case String:
				if (value == null)
					target.put(memberName, JSONObject.NULL);
				target.put(memberName, value);
				break;
			case DateTime:
				if (value == null)
					target.put(memberName, JSONObject.NULL);
					
				target.put(memberName, (long)(((Date)value).getTime()));
				break;
			case Binary:
			default: 
				throw new IllegalStateException("Not supported Mendix Membertype for member " + memberName);
			}
		}
			
		/**
		 * Reference
		 */
		else if (member instanceof MendixObjectReference){
			if (value != null) 
				value = identifierToJSON(context, (IMendixIdentifier) value);
			
			if (value == null)
				target.put(Utils.getShortMemberName(memberName), JSONObject.NULL);
			else
				target.put(Utils.getShortMemberName(memberName), value);
		}
		
		/**
		 * Referenceset
		 */
		else if (member instanceof MendixObjectReferenceSet){
			JSONArray ar = new JSONArray();
			if (value != null) {
				@SuppressWarnings("unchecked")
				List<IMendixIdentifier> ids = (List<IMendixIdentifier>) value;
				for(IMendixIdentifier id : ids) if (id != null) {
					Object url = identifierToJSON(context, id);
					if (url != null)
						ar.put(url);
				}
			}
			target.put(Utils.getShortMemberName(memberName), ar);			
		}
		
		else
			throw new IllegalStateException("Unimplemented membertype " + member.getClass().getSimpleName());
	}

}
