package communitycommons;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.mendix.core.Core;
import com.mendix.core.CoreException;
import com.mendix.core.objectmanagement.member.MendixAutoNumber;
import com.mendix.core.objectmanagement.member.MendixDateTime;
import com.mendix.core.objectmanagement.member.MendixEnum;
import com.mendix.core.objectmanagement.member.MendixObjectReference;
import com.mendix.core.objectmanagement.member.MendixObjectReferenceSet;
import com.mendix.systemwideinterfaces.core.IContext;
import com.mendix.systemwideinterfaces.core.IMendixIdentifier;
import com.mendix.systemwideinterfaces.core.IMendixObject;
import com.mendix.systemwideinterfaces.core.IMendixObject.ObjectState;
import com.mendix.systemwideinterfaces.core.IMendixObjectMember;
import com.mendix.systemwideinterfaces.core.IMendixObjectMember.MemberState;
import com.mendix.systemwideinterfaces.core.ISession;
import com.mendix.systemwideinterfaces.core.UserAction;
import com.mendix.systemwideinterfaces.core.meta.IMetaAssociation;
import com.mendix.systemwideinterfaces.core.meta.IMetaAssociation.AssociationType;
import com.mendix.systemwideinterfaces.core.meta.IMetaEnumValue;
import com.mendix.systemwideinterfaces.core.meta.IMetaEnumeration;
import com.mendix.systemwideinterfaces.core.meta.IMetaPrimitive.PrimitiveType;

public class ORM
{



	public static Long getGUID(IMendixObject item)
	{
		return item.getId().getGuid();
	}

	public static String getOriginalValueAsString(IContext context, IMendixObject item,
			String member)
	{
		return String.valueOf(item.getMember(context, member).getOriginalValue(context));
	}

	public static boolean objectHasChanged(IMendixObject anyobject) {
		if (anyobject == null)
			throw new IllegalArgumentException("The provided object is empty");
		return anyobject.isChanged();
	}

	/**
	 * checks whether a certain member of an object has changed. If the objects itself is still new, we consider to be changes as well.
	 * @param item
	 * @param member
	 * @param context
	 * @return
	 */
	public static boolean memberHasChanged(IContext context, IMendixObject item, String member)
	{
		if (item == null)
			throw new IllegalArgumentException("The provided object is empty");
		if (!item.hasMember(member))
			throw new IllegalArgumentException("Unknown member: " + member);
		return item.getMember(context, member).getState() == MemberState.CHANGED || item.getState() != ObjectState.NORMAL;
	}

	public static void refreshClass(UserAction<?> action, String objectType)
	{
		action.addRefreshClass(objectType);
	}

	public static void deepClone(IContext c, IMendixObject source, IMendixObject target, String membersToSkip, String membersToKeep, String reverseAssociations) throws CoreException
	{
		List<String> toskip = Arrays.asList((membersToSkip + ",createdDate,changedDate").split(","));
		List<String> tokeep = Arrays.asList((membersToKeep + ",System.owner,System.changedBy").split(","));
		List<String> revAssoc = Arrays.asList(reverseAssociations.split(","));
		duplicate(c, source, target, toskip, tokeep, revAssoc);
	}

	private static void duplicate(IContext c, IMendixObject sou, IMendixObject tar,
			List<String> toskip, List<String> tokeep, List<String> revAssoc) throws CoreException
	{
		Map<String, ? extends IMendixObjectMember<?>> members = sou.getMembers(c);
		String type = sou.getType() + "/";

		for(String key : members.keySet())
			if (!toskip.contains(key) && !toskip.contains(type + key)){
				IMendixObjectMember<?> m = members.get(key);
				if (m.isVirtual() || m instanceof MendixAutoNumber)
					continue;

				boolean keep = tokeep.contains(key) || tokeep.contains(type + key);

				if (m instanceof MendixObjectReference && !keep && m.getValue(c) != null) {
					IMendixObject o = Core.retrieveId(c, ((MendixObjectReference) m).getValue(c));
					IMendixObject target2 = Core.create(c, o.getType());
					duplicate(c, o, target2, toskip, tokeep, revAssoc);
					tar.setValue(c, key, target2.getId());
				}

				else if (m instanceof MendixObjectReferenceSet && !keep && m.getValue(c) != null) {
					MendixObjectReferenceSet rs = (MendixObjectReferenceSet) m;
					List<IMendixIdentifier> res = new ArrayList<IMendixIdentifier>();
					for(IMendixIdentifier item: rs.getValue(c)) {
						IMendixObject o = Core.retrieveId(c, item);
						IMendixObject target2 = Core.create(c, o.getType());
						duplicate(c, o, target2, toskip, tokeep, revAssoc);
						res.add(target2.getId());
					}
					tar.setValue(c, key, res);
				}

				else if (m instanceof MendixAutoNumber) //skip autonumbers! Ticket 14893
					continue;

				else {
					tar.setValue(c, key, m.getValue(c));
				}
			}
		Core.commit(c, tar);
		duplicateReverseAssociations(c, sou, tar, toskip, tokeep, revAssoc);
	}

	private static void duplicateReverseAssociations(IContext c, IMendixObject sou, IMendixObject tar, List<String> toskip, List<String> tokeep, List<String> revAssocs) throws CoreException
	{
		for(String fullAssocName : revAssocs) {
			String[] parts = fullAssocName.split("/");

			if (parts.length != 1 && parts.length != 3) //specifying entity has no meaning anymore, but remain backward compatible.
				throw new IllegalArgumentException("Reverse association is not defined correctly, please mention the relation name only: '" + fullAssocName + "'");

			String assocname = parts.length == 3 ? parts[1] : parts[0]; //support length 3 for backward compatibility

			IMetaAssociation massoc = sou.getMetaObject().getDeclaredMetaAssociationChild(assocname);

			if (massoc != null) {
				//MWE: what to do with reverse reference sets? -> to avoid spam creating objects on reverse references, do not support referenceset (todo: we could keep a map of converted guids and reuse that!)
				if (massoc.getType() == AssociationType.REFERENCESET)
					throw new IllegalArgumentException("It is not possible to clone reverse referencesets: '" + fullAssocName + "'");

				List<IMendixObject> objs = Core.retrieveXPathQueryEscaped(c, "//%s[%s='%s']", massoc.getParent().getName(), assocname, String.valueOf(sou.getId().getGuid()));
				List<String> toskip2 = new ArrayList<String>(toskip);
				toskip2.add(assocname); //do not duplicate the association

				for(IMendixObject obj : objs) {
					IMendixObject res = Core.create(c, obj.getType());
					duplicate(c, obj, res, toskip2, tokeep, revAssocs);

					//ReferenceSet has already thrown, so safe cast.
					((MendixObjectReference) res.getMember(c, assocname)).setValue(c, tar.getId());
					Core.commit(c, res);
				}
			}
		}
	}

	public static Boolean commitWithoutEvents(IContext context, IMendixObject subject)
	{
		Core.commitWithoutEvents(context, subject);
		return true;
	}

	public static String getValueOfPath(IContext context, IMendixObject substitute, String fullpath, String datetimeformat) throws Exception
	{
		String[] path = fullpath.split("/");
		if (path.length == 1) {
			IMendixObjectMember<?> member = substitute.getMember(context, path[0]);

			//special case, see ticket 9135, format datetime.
			if (member instanceof MendixDateTime) {
				Date time = ((MendixDateTime) member).getValue(context);
				if (time == null)
					return "";
				String f = datetimeformat != null && !datetimeformat.isEmpty() ? datetimeformat : "EEE dd MMM yyyy, HH:mm";
				return new SimpleDateFormat(f).format(time);
			}

			if (member instanceof MendixEnum) {
				String value = member.parseValueToString(context);
				if (value == null || value.isEmpty())
					return "";

				IMetaEnumeration enumeration = ((MendixEnum)member).getEnumeration();
				IMetaEnumValue evalue = enumeration.getEnumValues().get(value);
				return Core.getInternationalizedString(context, evalue.getI18NCaptionKey());
			}
			//default
			return member.parseValueToString(context);
		}

		else if (path.length == 0)
			throw new Exception("communitycommons.ORM.getValueOfPath: Unexpected end of path.");

		else {
			IMendixObjectMember<?> member = substitute.getMember(context, path[0]);
			if (member instanceof MendixObjectReference) {
				MendixObjectReference ref = (MendixObjectReference) member;
				IMendixIdentifier id = ref.getValue(context);
				if (id == null)
					return "";
				IMendixObject obj = Core.retrieveId(context, id);
				if (obj == null)
					return "";
				return getValueOfPath(context, obj, fullpath.substring(fullpath.indexOf("/") + 1), datetimeformat);
			}

			else if (member instanceof MendixObjectReferenceSet) {
				MendixObjectReferenceSet ref = (MendixObjectReferenceSet) member;
				List<IMendixIdentifier> ids = ref.getValue(context);
				if (ids == null)
					return "";
				StringBuilder res = new StringBuilder();
				for(IMendixIdentifier id : ids) {
					if (id == null)
						continue;
					IMendixObject obj = Core.retrieveId(context, id);
					if (obj == null)
						continue;
					res.append(", ");
					res.append(getValueOfPath(context, obj, fullpath.substring(fullpath.indexOf("/") + 1), datetimeformat));
				}
				return res.length() > 1 ? res.toString().substring(2) : "";
			}
			else throw new Exception("communitycommons.ORM.getValueOfPath: Not a valid reference: '"+path[0]+"' in '"+ fullpath +"'");
		}
	}

	public static Boolean cloneObject(IContext c, IMendixObject source,
			IMendixObject target, Boolean withAssociations)
	{
		Map<String, ? extends IMendixObjectMember<?>> members = source.getMembers(c);

		for(String key : members.keySet()) {
			IMendixObjectMember<?> m = members.get(key);
			if (m.isVirtual() || m instanceof MendixAutoNumber)
				continue;
			if (withAssociations || ((!(m instanceof MendixObjectReference) && !(m instanceof MendixObjectReferenceSet))))
				target.setValue(c, key, m.getValue(c));
		}
		return true;
	}

	private static ConcurrentHashMap<Long, ISession> locks = new ConcurrentHashMap<Long, ISession>();

	public static synchronized Boolean acquireLock(IContext context, IMendixObject item)
	{
		if (!isLocked(item)) {
			if (!context.getSession().getUser().getName().equals(getLockOwner(item)))
			locks.put(item.getId().getGuid(), context.getSession());
			return true;
		}
		else if (locks.get(item.getId().getGuid()).equals(context.getSession()))
			return true; //lock owned by this session
		return false;
	}

	private static boolean isLocked(IMendixObject item)
	{
		if (item == null)
			throw new IllegalArgumentException("No item provided");
		if (!locks.containsKey(item.getId().getGuid()))
			return false;
		if (!sessionIsActive(locks.get(item.getId().getGuid()))) {
			locks.remove(item.getId().getGuid()); //Remove locks which are nolonger active
			return false;
		}
		return true;
	}

	private static boolean sessionIsActive(ISession session)
	{
/* Pre 2.5.3 implementation:
 * if (Core.getConfiguration().enablePersistentSessions()) {
			List<IMendixObject> sessions = Core.retrieveXPathQuery(Core.getSystemContext(),String.format("//%s[%s='%s']",
					Session.entityName,
					Session.MemberNames.SessionId,
					session.getId()));
			return sessions.size() > 0;
		}
		return true;
*/
		for (ISession s : Core.getActiveSessions())
			if (s.equals(session))
				return true;
		return false;
	}

	public synchronized static Boolean releaseLock(IContext context, IMendixObject item, Boolean force)
	{
		if (locks.containsKey(item.getId().getGuid())) {
			if (force || locks.get(item.getId().getGuid()).equals(context.getSession()))
				locks.remove(item.getId().getGuid());
		}
		return true;
	}

	public static Boolean waitForLock(IContext context, IMendixObject item,
			Long timeOutSeconds) throws  InterruptedException
	{
		boolean res = false;
		long started = new Date().getTime();
		while (!res) {
			res = acquireLock(context, item);
			if (!res)
				Thread.sleep(1000);
			if (((new Date().getTime()) - started) > 1000 * timeOutSeconds)
				break;
		}
		return res;
	}

	public static String getLockOwner(IMendixObject item)
	{
		ISession session = locks.get(item.getId().getGuid());
		return session == null ? null : session.getUser().getName();
	}

	public static IMendixObject firstWhere(IContext c, String entityName,
			Object member, String value) throws CoreException
	{
		List<IMendixObject> items = Core.retrieveXPathQuery(c, String.format("//%s[%s =  '%s']", entityName, member, value), 1, 0, new HashMap<String, String>());
		if (items == null || items.size() == 0)
			return null;
		return items.get(0);
	}

	public synchronized static void releaseOldLocks()
	{
		Set<ISession> activeSessions = new HashSet<ISession>(Core.getActiveSessions()); //Lookup with Ord(log(n)) instead of Ord(n).

		List<Long> tbrm = new ArrayList<Long>();
		for (Entry<Long, ISession> lock : locks.entrySet())
			if (!activeSessions.contains(lock.getValue()))
				tbrm.add(lock.getKey());

		for(Long key : tbrm)
			locks.remove(key);
	}

	public static IMendixObject getLastChangedByUser(IContext context,
			IMendixObject thing) throws CoreException
	{
		if (thing == null || !thing.hasChangedByAttribute())
			return null;

		IMendixIdentifier itemId = thing.getChangedBy(context);
		if (itemId == null)
			return null;

		return Core.retrieveId(context, itemId);
	}

	public static IMendixObject getCreatedByUser(IContext context,
			IMendixObject thing) throws CoreException
	{
		if (thing == null || !thing.hasOwnerAttribute())
			return null;

		IMendixIdentifier itemId = thing.getOwner(context);
		if (itemId == null)
			return null;

		return Core.retrieveId(context, itemId);
	}

	public static boolean encryptMemberIfChanged(IContext context, IMendixObject item,
			String member, String key) throws Exception
	{
		if (memberHasChanged(context, item, member)) {

			if (item.getMetaObject().getMetaPrimitive(member).getType() != PrimitiveType.String)
				throw new IllegalArgumentException("The member '" + member + "' is not a string attribute!");

			item.setValue(context, member, StringUtils.encryptString(key, (String) item.getValue(context, member)));
			return true;
		}
		return false;
	}

	public static void commitSilent(IContext c, IMendixObject mendixObject)
	{
		try
		{
			Core.commit(c, mendixObject);
		}
		catch (CoreException e)
		{
			throw new RuntimeException(e);
		}
	}
}
