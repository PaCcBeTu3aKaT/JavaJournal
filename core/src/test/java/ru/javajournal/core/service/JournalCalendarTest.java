package ru.javajournal.core.service;

import org.junit.jupiter.api.Test;
import ru.javajournal.core.model.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class JournalCalendarTest {
    @Test void mergesVisitsAndGradesByDateAndSubject() {
        LocalDate d=LocalDate.of(2026,11,8);
        JournalData data=new JournalData(d,true,new Profile("Георгий","Группа","","","Георгий"),
            List.of(new Lesson("1",d,"Математика","08:00","09:20","","208")), List.of(),
            List.of(new Grade("g","Математика",5,"Домашняя работа","",d)),List.of(),
            List.of(new AttendanceEntry(d,"Математика",true,AttendanceState.late,"2")),Map.of());
        var day=JournalCalendar.forDay(data,d);
        assertEquals(1,day.events().size());
        assertTrue(day.hasAttendance(AttendanceState.late));
        assertEquals(5,day.events().get(0).grades().get(0).value());
        assertTrue(JournalCalendar.forDay(data,d.plusDays(1)).events().isEmpty());
    }
    @Test void multipleLessonsOnOneDayKeepTheirOwnGradesAndDots() {
        LocalDate d=LocalDate.of(2026,11,9);
        JournalData data=new JournalData(d,true,new Profile("A","Group","","","A"),
            List.of(new Lesson("11",d,"Математика","08:00","09:20","Teacher","208"),
                new Lesson("12",d,"Математика","09:30","10:50","Teacher","208")),List.of(),
            List.of(new Grade("12-home_work_mark-2026-11-09","Математика",5,"Домашняя работа","",d)),
            List.of(),List.of(new AttendanceEntry(d,"Математика",true,AttendanceState.present,"1","11")),Map.of());
        var day=JournalCalendar.forDay(data,d);
        assertEquals(2,day.events().size());
        assertTrue(day.events().get(0).grades().isEmpty());
        assertEquals(1,day.events().get(1).grades().size());
    }
}
