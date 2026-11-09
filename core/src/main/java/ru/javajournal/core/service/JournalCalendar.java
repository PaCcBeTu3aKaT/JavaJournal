package ru.javajournal.core.service;

import ru.javajournal.core.model.*;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

/** Calendar shared by desktop and Android. Never guesses an undocumented late status. */
public final class JournalCalendar {
    private JournalCalendar() {}
    public record LessonEvent(String subject, String time, AttendanceState attendance,
                              List<Grade> grades, String room, String teacher) {
        public LessonEvent { grades = List.copyOf(grades); }
    }
    public record Day(LocalDate date, List<LessonEvent> events, List<Grade> unmatchedGrades) {
        public Day { events = List.copyOf(events); unmatchedGrades = List.copyOf(unmatchedGrades); }
        public boolean hasAttendance(AttendanceState state) {
            return events.stream().anyMatch(e -> e.attendance() == state);
        }
        public boolean hasGrades() { return events.stream().anyMatch(e -> !e.grades().isEmpty()) || !unmatchedGrades.isEmpty(); }
    }
    public static boolean isUpcoming(LocalDate day, LessonEvent event) {
        if (day == null || event == null || event.attendance() != AttendanceState.unknown) return false;
        java.time.LocalDate today = LocalDate.now();
        if (day.isAfter(today)) return true;
        if (day.isBefore(today)) return false;
        try {
            String start = event.time().split("–")[0].trim();
            return java.time.LocalTime.now().isBefore(java.time.LocalTime.parse(start));
        } catch (RuntimeException ignored) { return false; }
    }
    public static Day forDay(JournalData data, LocalDate date) {
        if (data == null || date == null) return new Day(date, List.of(), List.of());
        List<MutableEvent> events = new ArrayList<>();
        for (Lesson lesson : data.lessons()) if (date.equals(lesson.date())) {
            MutableEvent e = new MutableEvent(lesson.subject(), lesson.id());
            e.time = lesson.start() + "–" + lesson.end();
            e.room = lesson.room(); e.teacher = lesson.teacher();
            events.add(e);
        }
        for (AttendanceEntry visit : data.attendanceEntries()) if (date.equals(visit.date())) {
            MutableEvent exact = findById(events, visit.lessonId());
            MutableEvent target = exact != null ? exact : events.stream()
                .filter(e -> normalize(e.subject).equals(normalize(visit.subject())) && !e.attendanceAssigned)
                .findFirst().orElse(null);
            if (target == null) { target = new MutableEvent(visit.subject(), visit.lessonId()); events.add(target); }
            target.state = visit.state(); target.attendanceAssigned = true;
            if (target.id.isBlank()) target.id = visit.lessonId();
        }
        List<Grade> unmatched = new ArrayList<>();
        Set<String> gradeIds = new HashSet<>();
        for (Grade grade : data.grades()) if (date.equals(grade.date()) && gradeIds.add(grade.id())) {
            MutableEvent target = events.stream()
                .filter(e -> !e.id.isBlank() && grade.id().startsWith(e.id + "-"))
                .findFirst().orElse(null);
            if (target == null) {
                List<MutableEvent> subjectMatches = events.stream()
                    .filter(e -> normalize(e.subject).equals(normalize(grade.subject()))).toList();
                if (subjectMatches.size() == 1) target = subjectMatches.get(0);
            }
            if (target != null) target.grades.add(grade);
            else unmatched.add(grade);
        }
        List<LessonEvent> result = new ArrayList<>();
        for (MutableEvent e : events)
            result.add(new LessonEvent(e.subject, e.time, e.state, e.grades, e.room, e.teacher));
        return new Day(date, result, unmatched);
    }
    public static List<Day> forMonth(JournalData data,YearMonth month){
        List<Day> days=new ArrayList<>();
        for(int i=1;i<=month.lengthOfMonth();i++)days.add(forDay(data,month.atDay(i)));
        return List.copyOf(days);
    }
    private static MutableEvent findById(List<MutableEvent> events,String id){
        if(id==null||id.isBlank())return null;
        return events.stream().filter(e->id.equals(e.id)&&!e.attendanceAssigned).findFirst().orElse(null);
    }
    private static String normalize(String s){return s==null?"":s.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+"," ");}
    private static final class MutableEvent {
        final String subject;
        String id,time="",room="",teacher="";
        AttendanceState state=AttendanceState.unknown;
        boolean attendanceAssigned;
        final List<Grade> grades=new ArrayList<>();
        MutableEvent(String subject,String id){this.subject=subject;this.id=id==null?"":id;}
    }
}
