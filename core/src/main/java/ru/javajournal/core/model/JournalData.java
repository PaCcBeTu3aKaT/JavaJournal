package ru.javajournal.core.model;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public record JournalData(LocalDate date, boolean week, Profile profile,
                          List<Lesson> lessons, List<Homework> homework,
                          List<Grade> grades, List<Attendance> attendance,
                          List<AttendanceEntry> attendanceEntries, Map<String, String> warnings) {
    public JournalData {
        lessons = List.copyOf(lessons);
        homework = List.copyOf(homework);
        grades = List.copyOf(grades);
        attendance = List.copyOf(attendance);
        attendanceEntries = List.copyOf(attendanceEntries);
        warnings = Map.copyOf(warnings);
    }
    /** Совместимость с источниками, не имеющими записей по отдельным датам. */
    public JournalData(LocalDate date, boolean week, Profile profile,
                       List<Lesson> lessons, List<Homework> homework,
                       List<Grade> grades, List<Attendance> attendance,
                       Map<String, String> warnings) {
        this(date, week, profile, lessons, homework, grades, attendance, List.of(), warnings);
    }
    public double averageGrade() { return grades.stream().mapToInt(Grade::value).average().orElse(0); }
    public double attendancePercent() {
        int visited = attendance.stream().mapToInt(Attendance::visited).sum();
        int total = attendance.stream().mapToInt(Attendance::total).sum();
        return total == 0 ? 0 : 100.0 * visited / total;
    }
    public long activeHomework() { return homework.stream().filter(h -> h.status() == HomeworkStatus.pending || h.status() == HomeworkStatus.overdue).count(); }
}
