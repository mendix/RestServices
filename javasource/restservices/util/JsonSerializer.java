package restservices.util;

import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONObject;

import restservices.RestServices;
import restservices.publish.DataService;
import restservices.proxies.BooleanValue;

import com.mendix.core.Core;
import com.mendix.core.objectmanagement.member.MendixEnum;
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
		return identifierToJSON(context, id, new HashSet<Long>());
	}
	
	
	private static Object identifierToJSON(IContext context, IMendixIdentifier id, Set<Long> alreadySeen) throws Exception {
		if (id == null)
			return null;
		
		if (alreadySeen.contains(id.toLong())) {
			RestServices.LOGUTIL.warn("ID already seen: " + id.toLong() + ", skipping serialization");
			return null;
		}
		alreadySeen.add(id.toLong());
		
		/* persistable object, generate url */
		if (Core.getMetaObject(id.getObjectType()).isPersistable()) {
		
			DataService service = RestServices.getServiceForEntity(id.getObjectType());
			if (service == null) {
				RestServices.LOGUTIL.warn("No RestService has been definied for type: " + id.getObjectType() + ", identifier could not be serialized");
				return null;
			}
		
			IMendixObject obj = Core.retrieveId(context, id); //Optimize: for refset use retrieve ids
			if (obj == null) {
				RestServices.LOGUTIL.warn("Failed to retrieve identifier: " + id + ", does the object still exist?");
				return null;
			}
			if (Utils.isValidKey(service.getKey(context, obj)))
				return service.getObjecturl(context, obj);

			return null;
		}
		
		/* Non persistable object, write the object itself */
		IMendixObject obj = Core.retrieveId(context, id); //Optimize: for refset use retrieve ids 
		if (obj == null) {
			RestServices.LOGUTIL.warn("Failed to retrieve identifier: " + id + ", does the object still exist?");
			return null;
		}
		return writeMendixObjectToJson(context, obj, alreadySeen);
	}

	public static JSONObject writeMendixObjectToJson(IContext context, IMendixObject view) throws Exception {
		return writeMendixObjectToJson(context, view, new HashSet<Long>());
	}
	
	private static JSONObject writeMendixObjectToJson(IContext context, IMendixObject view, Set<Long> alreadySeen) throws Exception {
		if (view == null)
			throw new IllegalArgumentException("Mendix to JSON conversion expects an object");
		
		if (!Utils.hasDataAccess(view.getMetaObject(), context))
			throw new IllegalStateException("During JSON serialization: Object of type '" + view.getType() + "' has no readable members for users with role(s) " + context.getSession().getUserRolesNames() + ". Please check the security rules");
		
		JSONObject res = new JSONObject();

		Map<String, ? extends IMendixObjectMember<?>> members = view.getMembers(context);
		for(java.util.Map.Entry<String, ? extends IMendixObjectMember<?>> e : members.entrySet())
			serializeMember(context, res, e.getValue(), view.getMetaObject(), alreadySeen);
		
		return res;
	}

	private static void serializeMember(IContext context, JSONObject target,
			IMendixObjectMember<?> member, IMetaObject viewType, Set<Long> alreadySeen) throws Exception {
		if (context == null)
			throw new IllegalStateException("Context is null");
	
		Object value = member.getValue(context);
		String memberName = member.getName();
		
		if (Utils.isSystemAttribute(memberName)) {
			//skip
		}
		//Primitive?
		else if (!(member instanceof MendixObjectReference) && !(member instanceof MendixObjectReferenceSet)) {
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
				//Support for built-in BooleanValue enumeration.
				MendixEnum me = (MendixEnum) member;
				if ("RestServices.BooleanValue".equals(me.getEnumeration().getName())) {
					if (BooleanValue._true.toString().equals(me.getValue(context)))
						target.put(memberName, true);
					else if (BooleanValue._false.toString().equals(me.getValue(context)))
						target.put(memberName, false);
					break;
				}
				
				//other enumeration, fall trough intentional
			case HashString:
			case String:
				if (value == null)
					target.put(memberName, JSONObject.NULL);
				else
					target.put(memberName, value);
				break;
			case DateTime:
				if (value == null)
					target.put(memberName, JSONObject.NULL);
				else	
					target.put(memberName, (((Date)value).getTime()));
				break;
			case Binary:
				break;
			default: 
				throw new IllegalStateException("Not supported Mendix Membertype for member " + memberName);
			}
		}
			
		/**
		 * Reference
		 */
		else if (member instanceof MendixObjectReference){
			if (value != null) 
				value = identifierToJSON(context, (IMendixIdentifier) value, alreadySeen);
			
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
					Object url = identifierToJSON(context, id, alreadySeen);
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
