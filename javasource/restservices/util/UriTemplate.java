package restservices.util;

import java.util.List;
import java.util.Map;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;

public class UriTemplate {

	Pattern regex;
	List<String> paramNames = Lists.newArrayList();
	
	public UriTemplate(String name) {
		String re = Pattern.quote(Utils.removeLeadingAndTrailingSlash(name));
		re = re.replaceAll("(^\\\\Q|\\\\E$)", "");
		re = "^\\/?" + re + "\\/?$";
		
		re = regexReplaceAll(re, "\\{([a-zA-Z_0-9]+)\\}", new Function<MatchResult, String>() {

			@Override
			public String apply(MatchResult match) {
				paramNames.add(match.group(1));
				return "(.+?)";
			}
		});
		
		regex = Pattern.compile(re, Pattern.CASE_INSENSITIVE);
	}
	
	public boolean match(String uri, Map<String, String> params) {
		Preconditions.checkNotNull(uri);
		
		Matcher matcher = regex.matcher(uri);
		if (!matcher.matches())
			return false;
		
		for (int i = 0; i < matcher.groupCount(); i++) {
			params.put(paramNames.get(i), Utils.urlDecode(matcher.group(i + 1)));
		}
		return true;
	}
	
	//From community commons StringUtils 4.3.3
	public static String regexReplaceAll(String source, String regexString, Function<MatchResult, String> replaceFunction)  {
		if (source == null || source.trim().isEmpty()) // avoid NPE's, save CPU
			return "";
	
		StringBuffer resultString = new StringBuffer();
		Pattern regex = Pattern.compile(regexString);
		Matcher regexMatcher = regex.matcher(source);
		
		while (regexMatcher.find()) {
			MatchResult match = regexMatcher.toMatchResult();
			String value = replaceFunction.apply(match); 
			regexMatcher.appendReplacement(resultString, Matcher.quoteReplacement(value));
		}
		regexMatcher.appendTail(resultString);
	
		return resultString.toString();
	}
}
