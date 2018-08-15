package communitycommons;

import java.util.Calendar;
import java.util.Date;

import communitycommons.proxies.DatePartSelector;

public class DateTime
{
	/**
	 * @author mwe
	 * Berekent aantal jaar sinds een bepaalde datum. Als einddatum == null, het huidige tijdstip wordt gebruikt
	 * Code is gebaseerd op http://stackoverflow.com/questions/1116123/how-do-i-calculate-someones-age-in-java 
	 */
	public static long yearsBetween(Date birthdate, Date comparedate) {
		if (birthdate == null)
			return -1L; 
		
		Calendar now = Calendar.getInstance();
		if (comparedate != null)
			now.setTime(comparedate);
		Calendar dob = Calendar.getInstance();
		dob.setTime(birthdate);
		if (dob.after(now)) 
			return -1L;
		
		int year1 = now.get(Calendar.YEAR);
		int year2 = dob.get(Calendar.YEAR);
		long age = year1 - year2;
		int month1 = now.get(Calendar.MONTH);
		int month2 = dob.get(Calendar.MONTH);
		if (month2 > month1) {
		  age--;
		} else if (month1 == month2) {
		  int day1 = now.get(Calendar.DAY_OF_MONTH);
		  int day2 = dob.get(Calendar.DAY_OF_MONTH);
		  if (day2 > day1) {
		    age--;
		  }
		}
		return age;		
	}

	public static long dateTimeToLong(Date date)
	{
		return date.getTime();
	}

	public static Date longToDateTime(Long value)
	{
		return new Date(value);
	}
	
	public static long dateTimeToInteger(Date date, DatePartSelector selectorObj)
	{
		Calendar newDate = Calendar.getInstance();
		newDate.setTime(date);
		int value = -1;
		switch (selectorObj) {
			case year : value = newDate.get(Calendar.YEAR); break;
			case month : value = newDate.get(Calendar.MONTH)+1; break; // Return starts at 0
			case day : value = newDate.get(Calendar.DAY_OF_MONTH); break;
			default : break;
		}
		return value;
	}
		
}
