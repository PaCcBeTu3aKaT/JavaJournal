package ru.javajournal.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.Editable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;

import ru.javajournal.core.api.TopAcademySource;
import ru.javajournal.core.model.*;
import ru.javajournal.core.service.JournalCalendar;
import ru.javajournal.core.service.JournalDisplay;
import ru.javajournal.core.service.JournalFilters;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/** Native Java view hierarchy, sharing the JavaJournal core with desktop. */
public final class MainActivity extends Activity {
    private static final int saveDocumentRequest = 720;
    private static final int chooseUploadRequest = 721;
    private static final int darkBackground = Color.rgb(16,22,34);
    private static final int darkSurface = Color.rgb(28,39,56);
    private static final int darkCard = Color.rgb(40,57,78);
    private static final int lightBackground = Color.rgb(244,247,252);
    private static final int lightSurface = Color.WHITE;
    private static final int lightCard = Color.rgb(232,239,248);
    private static final int accent = Color.rgb(115,149,240);
    private static final int cyan = Color.rgb(125,196,244);
    private static final int green = Color.rgb(106,208,158);
    private static final int amber = Color.rgb(236,180,83);
    private static final int red = Color.rgb(227,112,114);
    private static final Locale russian = Locale.forLanguageTag("ru-RU");
    private static final DateTimeFormatter dateFormat = DateTimeFormatter.ofPattern("d MMMM yyyy", russian);
    private static final DateTimeFormatter monthFormat = DateTimeFormatter.ofPattern("LLLL yyyy", russian);

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private SessionStore sessions;
    private TopAcademySource source;
    private JournalData data;
    private String currentPage = "Главная";
    private Homework selectedHomework;
    private Uri selectedUpload;
    private String uploadFilename = "";
    private String draftAnswer = "";
    private boolean dark = true;
    private boolean showWeek = true;
    private boolean fetching = false;
    private boolean moreOpen = false;
    private int requestSequence = 0;
    private int homeworkScroll = 0;
    private LocalDate selectedDate = JournalDisplay.startingWeekDate(LocalDate.now());
    private YearMonth calendarMonth = YearMonth.now();
    private LocalDate calendarDay = LocalDate.now();
    private JournalFilters.Period period = JournalFilters.Period.month;
    private LocalDate periodDate = LocalDate.now();
    private JournalFilters.HomeworkSort homeworkSort = JournalFilters.HomeworkSort.dueAscending;
    private HomeworkStatus homeworkFilter;
    private Path downloadedFile;
    private FrameLayout frame;
    private LinearLayout root;
    private LinearLayout content;
    private ScrollView pageScroll;
    private View updateBanner;
    private boolean bannerDismissed;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(darkBackground);
        getWindow().setNavigationBarColor(darkBackground);
        sessions = new SessionStore(this);
        String saved = sessions.load();
        if (saved == null) { showLogin(null); return; }
        // Restore session in a worker; show the shell without a full-screen splash.
        fetching = true;
        showPage();
        beginBannerDelay();
        work(() -> TopAcademySource.restoreSession(saved), restored -> {
            source = restored;
            loadData();
        }, error -> {
            finishFetch();
            if (error instanceof TopAcademySource.AuthException) sessions.clear();
            showLogin(error instanceof TopAcademySource.AuthException ?
                "Сессия истекла. Войди снова." : "Не удалось восстановить сессию. Проверь интернет.");
        });
    }

    private int background() { return dark ? darkBackground : lightBackground; }
    private int surface() { return dark ? darkSurface : lightSurface; }
    private int cardColor() { return dark ? darkCard : lightCard; }
    private int primary() { return dark ? Color.WHITE : Color.rgb(27,41,60); }
    private int muted() { return dark ? Color.rgb(173,190,214) : Color.rgb(94,111,133); }
    private int borderColor() { return dark ? Color.rgb(69,91,119) : Color.rgb(200,213,231); }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private GradientDrawable shape(int color, int radius, boolean border) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius));
        if (border) d.setStroke(dp(1), borderColor());
        return d;
    }
    private GradientDrawable gradient() {
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
            new int[] {Color.rgb(54,125,220),Color.rgb(121,157,238)});
        d.setCornerRadius(dp(19)); return d;
    }
    private TextView label(String text, int size, boolean bold, int color) {
        TextView t = new TextView(this); t.setText(text); t.setTextSize(size);t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        t.setIncludeFontPadding(true);
        return t;
    }
    private LinearLayout vertical() { LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l; }
    private LinearLayout horizontal() { LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);l.setGravity(Gravity.CENTER_VERTICAL);return l; }
    private void pad(View v,int all) { v.setPadding(dp(all),dp(all),dp(all),dp(all)); }
    private void gap(LinearLayout l,int height) { l.addView(new View(this),new LinearLayout.LayoutParams(1,dp(height))); }
    private void txt(LinearLayout l,String text,int size,boolean bold) { l.addView(label(text,size,bold,primary())); }
    private void secondary(LinearLayout l,String text) {
        TextView t=label(text,14,false,muted());t.setPadding(0,dp(5),0,0);l.addView(t);
    }
    private LinearLayout card() {
        LinearLayout l=vertical();l.setBackground(shape(cardColor(),17,true));pad(l,16);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=dp(13);l.setLayoutParams(p);
        return l;
    }
    private Button button(String title,Runnable action,boolean prominent) {
        Button b=new Button(this);b.setText(title);b.setAllCaps(false);b.setTextSize(13);
        b.setMinHeight(0);b.setMinimumHeight(0);b.setMinWidth(0);b.setMinimumWidth(0);
        b.setTextColor(prominent?Color.rgb(17,31,56):primary());
        b.setBackground(shape(prominent?accent:cardColor(),12,true));
        b.setPadding(dp(11),0,dp(11),0);
        if(action!=null)b.setOnClickListener(v->action.run());return b;
    }
    private LinearLayout.LayoutParams weight(int height) { return new LinearLayout.LayoutParams(0,dp(height),1); }
    private void setupFrame(View page) {
        frame = new FrameLayout(this);frame.setBackgroundColor(background());
        frame.addView(page,new FrameLayout.LayoutParams(-1,-1));
        setContentView(frame);
    }
    private EditText field(String hint,boolean password) {
        EditText e=new EditText(this);e.setTextSize(16);e.setSingleLine(true);
        e.setHint(hint);e.setHintTextColor(muted());e.setTextColor(primary());
        e.setBackground(shape(cardColor(),12,true));pad(e,14);
        if(password)e.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        return e;
    }
    private void showLogin(String message) {
        fetching=false;root=vertical();root.setBackgroundColor(background());pad(root,20);
        ScrollView sc=new ScrollView(this);sc.setFillViewport(true);
        root.addView(sc,new LinearLayout.LayoutParams(-1,-1));
        LinearLayout holder=vertical();holder.setGravity(Gravity.CENTER);
        sc.addView(holder,new ScrollView.LayoutParams(-1,-1));
        LinearLayout form=vertical();form.setGravity(Gravity.CENTER_HORIZONTAL);
        form.setBackground(shape(surface(),22,true));pad(form,22);
        ImageView logo=new ImageView(this);logo.setImageResource(R.drawable.javajournal_logo);
        form.addView(logo,new LinearLayout.LayoutParams(dp(78),dp(78)));gap(form,14);
        txt(form,"Добро пожаловать",24,true);gap(form,22);
        EditText login=field("Логин",false),password=field("Пароль",true);
        form.addView(login,new LinearLayout.LayoutParams(-1,dp(52)));gap(form,12);
        form.addView(password,new LinearLayout.LayoutParams(-1,dp(52)));gap(form,17);
        Button enter=button("Войти",null,true);form.addView(enter,new LinearLayout.LayoutParams(-1,dp(50)));
        ProgressBar progress=new ProgressBar(this);progress.setVisibility(View.GONE);
        LinearLayout.LayoutParams pp=new LinearLayout.LayoutParams(dp(27),dp(27));pp.gravity=Gravity.CENTER;
        pp.topMargin=dp(12);form.addView(progress,pp);
        enter.setOnClickListener(v->{
            String username=login.getText().toString().trim();String pass=password.getText().toString();
            if(username.isEmpty()||pass.isEmpty()){toast("Введи логин и пароль");return;}
            enter.setEnabled(false);progress.setVisibility(View.VISIBLE);
            work(()->TopAcademySource.login(username,pass),logged->{
                source=logged;try{sessions.save(logged.sessionToken());}catch(Exception ex){toast("Не удалось сохранить сессию");}
                loadData();
            },error->{enter.setEnabled(true);progress.setVisibility(View.GONE);
                toast("Ошибка входа: "+safeError(error));});
        });
        if(message!=null&&!message.isBlank()){gap(form,10);secondary(form,message);}
        holder.addView(form,new LinearLayout.LayoutParams(-1,-2));
        setupFrame(root);
    }
    private interface Action<T> { T run() throws Exception; }
    private interface Success<T> { void done(T value); }
    private interface Failure { void failed(Exception ex); }
    private <T> void work(Action<T> action,Success<T> success,Failure failure) {
        executor.execute(()->{try{T result=action.run();handler.post(()->{if(!isFinishing())success.done(result);});}
            catch(Exception ex){handler.post(()->{if(!isFinishing())failure.failed(ex);});}});
    }
    private String safeError(Exception e) { return e.getMessage()==null?"Ошибка соединения":e.getMessage(); }
    private void toast(String text) { Toast.makeText(this,text,Toast.LENGTH_LONG).show(); }

    private void beginBannerDelay() {
        bannerDismissed=false;
        int sequence=requestSequence;
        handler.postDelayed(()->{
            if(fetching&&!bannerDismissed&&sequence==requestSequence&&frame!=null)showUpdateBanner();
        },1000);
    }
    private void showUpdateBanner() {
        if(updateBanner!=null&&updateBanner.getParent()!=null)return;
        LinearLayout capsule=horizontal();capsule.setGravity(Gravity.CENTER);pad(capsule,11);
        capsule.setBackground(shape(dark?Color.rgb(48,67,92):Color.WHITE,35,true));
        ProgressBar progress=new ProgressBar(this);progress.setIndeterminateTintList(ColorStateList.valueOf(accent));
        capsule.addView(progress,new LinearLayout.LayoutParams(dp(19),dp(19)));
        TextView t=label("Обновление...",13,true,primary());t.setPadding(dp(9),0,0,0);capsule.addView(t);
        FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.CENTER_HORIZONTAL);
        lp.topMargin=dp(9);frame.addView(capsule,lp);updateBanner=capsule;
        capsule.setTranslationY(-dp(75));capsule.setAlpha(0f);
        capsule.animate().translationY(0).alpha(1f).setDuration(200).start();
        capsule.setOnTouchListener(new View.OnTouchListener(){float start;
            @Override public boolean onTouch(View view,MotionEvent event){
                if(event.getAction()==MotionEvent.ACTION_DOWN){start=event.getY();return true;}
                if(event.getAction()==MotionEvent.ACTION_UP){
                    if(event.getY()-start < -dp(14)){bannerDismissed=true;hideBanner();}
                    return true;
                }return true;
            }
        });
    }
    private void hideBanner() {
        View banner=updateBanner;updateBanner=null;
        if(banner==null)return;
        banner.animate().alpha(0f).translationY(-dp(70)).setDuration(180)
            .withEndAction(()->{if(banner.getParent() instanceof ViewGroup p)p.removeView(banner);}).start();
    }
    private void finishFetch() { fetching=false;requestSequence++;hideBanner(); }
    private void loadData() {
        if(source==null){showLogin(null);return;}
        fetching=true;requestSequence++;
        if(data==null || "Расписание".equals(currentPage))showPage();
        beginBannerDelay();
        int sequence=requestSequence;
        work(()->source.load(selectedDate,showWeek),loaded->{
            if(sequence!=requestSequence)return;
            data=loaded;finishFetch();showPage();
        },error->{
            if(sequence!=requestSequence)return;
            finishFetch();
            if(error instanceof TopAcademySource.AuthException){sessions.clear();source=null;data=null;showLogin("Сессия истекла.");}
            else {showPage();toast("Не удалось обновить данные: "+safeError(error));}
        });
    }
    private void refresh() {if(source!=null&&!fetching){source.refresh();loadData();}}

    private void showPage() {
        root=vertical();root.setBackgroundColor(background());
        LinearLayout top=horizontal();top.setPadding(dp(16),dp(14),dp(16),dp(14));
        top.setBackgroundColor(surface());
        TextView title=label(currentPage,23,true,primary());top.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        Button update=button("↻",this::refresh,false);
        top.addView(update,new LinearLayout.LayoutParams(dp(51),dp(45)));root.addView(top);
        pageScroll=new ScrollView(this);pageScroll.setFillViewport(true);
        root.addView(pageScroll,new LinearLayout.LayoutParams(-1,0,1));
        content=vertical();pad(content,16);pageScroll.addView(content);
        if(data==null){
            LinearLayout p=card();txt(p,"Загружаем данные",18,true);
            secondary(p,"Информация появится после подключения к журналу.");content.addView(p);
        } else switch(currentPage){
            case "Главная" -> home();
            case "Расписание" -> schedule();
            case "Задания" -> homework();
            case "Оценки" -> grades();
            case "Посещаемость" -> attendance();
            case "Календарь" -> calendar();
            case "Настройки" -> settings();
            default -> profile();
        }
        if(moreOpen)root.addView(morePanel());
        root.addView(navigation());
        setupFrame(root);
        if(fetching)beginBannerDelay();
        if("Задания".equals(currentPage)&&selectedHomework==null&&homeworkScroll>0){
            pageScroll.post(()->pageScroll.scrollTo(0,homeworkScroll));
        }
    }
    private LinearLayout navigation() {
        LinearLayout nav=horizontal();nav.setPadding(dp(5),dp(8),dp(5),dp(8));nav.setBackgroundColor(surface());
        String[] pages={"Главная","Расписание","Задания","Оценки","Ещё"};
        for(String page:pages){
            boolean active=page.equals(currentPage)||(page.equals("Ещё")&&isMorePage());
            LinearLayout item=vertical();item.setGravity(Gravity.CENTER);
            View dot=new View(this);dot.setBackground(shape(active?accent:muted(),10,false));
            LinearLayout.LayoutParams dotLp=new LinearLayout.LayoutParams(dp(7),dp(7));dotLp.gravity=Gravity.CENTER;
            item.addView(dot,dotLp);gap(item,5);
            TextView name=label(page,10,active,active?accent:muted());name.setGravity(Gravity.CENTER);
            name.setMaxLines(1);name.setAutoSizeTextTypeUniformWithConfiguration(8,10,1,android.util.TypedValue.COMPLEX_UNIT_SP);
            item.addView(name);
            item.setOnClickListener(v->{
                if("Ещё".equals(page)){moreOpen=!moreOpen;showPage();}
                else navigate(page);
            });
            nav.addView(item,new LinearLayout.LayoutParams(0,dp(54),1));
        }
        return nav;
    }
    private boolean isMorePage() {
        return "Календарь".equals(currentPage)||"Посещаемость".equals(currentPage)
            ||"Профиль".equals(currentPage)||"Настройки".equals(currentPage);
    }
    private View morePanel() {
        LinearLayout panel=vertical();panel.setPadding(dp(16),dp(10),dp(16),dp(10));
        panel.setBackgroundColor(surface());
        String[] others={"Календарь","Посещаемость","Профиль","Настройки"};
        for(int i=0;i<others.length;i+=2){
            LinearLayout row=horizontal();
            for(int j=i;j<Math.min(i+2,others.length);j++){
                String page=others[j];Button item=button(page,()->navigate(page),page.equals(currentPage));
                LinearLayout.LayoutParams lp=weight(46);lp.setMargins(dp(4),dp(4),dp(4),dp(4));row.addView(item,lp);
            }
            panel.addView(row);
        }
        return panel;
    }
    private void navigate(String page) {
        moreOpen=false;selectedHomework=null;currentPage=page;
        if(!"Задания".equals(page))homeworkScroll=0;
        showPage();
    }
    private void sectionTitle(LinearLayout parent,String title,Runnable action,String link) {
        LinearLayout row=horizontal();TextView t=label(title,18,true,primary());
        row.addView(t,new LinearLayout.LayoutParams(0,-2,1));
        if(action!=null){TextView right=label(link,12,true,accent);right.setOnClickListener(v->action.run());row.addView(right);}
        parent.addView(row);gap(parent,12);
    }
    private void home() {
        LinearLayout welcome=vertical();welcome.setBackground(gradient());pad(welcome,19);
        welcome.addView(label("Привет, "+data.profile().givenName()+"!",24,true,Color.WHITE));
        LinearLayout.LayoutParams wp=new LinearLayout.LayoutParams(-1,-2);wp.bottomMargin=dp(15);
        content.addView(welcome,wp);
        int weekCount=0;LocalDate today=LocalDate.now();LocalDate monday=JournalDisplay.startingWeekDate(today).with(DayOfWeek.MONDAY);
        for(Lesson l:data.lessons())if(!l.date().isBefore(monday)&&!l.date().isAfter(monday.plusDays(6)))weekCount++;
        LinearLayout rowOne=horizontal(),rowTwo=horizontal();
        addMetric(rowOne,"Занятий на неделе",weekCount+"","Расписание",cyan,()->navigate("Расписание"));
        addMetric(rowOne,"Активных заданий",String.valueOf(data.activeHomework()),"Нужно сделать",amber,()->navigate("Задания"));
        addMetric(rowTwo,"Средний балл",String.format(russian,"%.1f",data.averageGrade()),"Все оценки",green,()->navigate("Оценки"));
        addMetric(rowTwo,"Посещаемость",String.format(russian,"%.0f%%",data.attendancePercent()),"Все занятия",accent,()->navigate("Посещаемость"));
        content.addView(rowOne);content.addView(rowTwo);gap(content,4);
        LinearLayout next=card();sectionTitle(next,"Ближайшее занятие",()->navigate("Расписание"),"Всё расписание ›");
        List<Lesson> lessons=data.lessons().stream().filter(l->today.equals(l.date())).collect(Collectors.toList());
        Optional<Lesson> upcoming=JournalDisplay.nearestTodayLesson(lessons,today,LocalTime.now());
        if(upcoming.isEmpty())secondary(next,"На сегодня занятий больше нет.");
        else {Lesson l=upcoming.get();txt(next,l.subject(),17,true);
            secondary(next,l.start()+"–"+l.end()+" · "+(JournalDisplay.lessonInProgress(l,LocalTime.now())?"Идёт сейчас":"Следующее занятие"));}
        content.addView(next);
        LinearLayout hw=card();sectionTitle(hw,"Домашние задания",()->navigate("Задания"),"Все задания ›");
        List<Homework> pending=new ArrayList<>();for(Homework h:data.homework())
            if(h.status()==HomeworkStatus.pending||h.status()==HomeworkStatus.overdue)pending.add(h);
        pending.sort(Comparator.comparing(Homework::due,Comparator.nullsLast(Comparator.naturalOrder())));
        for(int i=0;i<Math.min(3,pending.size());i++){
            Homework h=pending.get(i);LinearLayout item=vertical();item.setBackground(shape(surface(),12,false));pad(item,12);
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=dp(8);
            TextView subject=label(h.subject(),12,true,cyan);item.addView(subject);
            txt(item,h.title(),14,true);secondary(item,"До "+(h.due()==null?"—":h.due().format(dateFormat)));
            item.setOnClickListener(v->{currentPage="Задания";openHomework(h);});hw.addView(item,lp);
        }
        if(pending.isEmpty())secondary(hw,"Активных заданий нет");content.addView(hw);
        if(!data.warnings().isEmpty()){LinearLayout warn=card();txt(warn,"Данные загружены не полностью",14,true);
            for(String msg:data.warnings().values())secondary(warn,msg);content.addView(warn);}
    }
    private void addMetric(LinearLayout row,String heading,String value,String foot,int color,Runnable click) {
        LinearLayout c=vertical();c.setBackground(shape(cardColor(),16,true));pad(c,13);
        LinearLayout headingRow=horizontal();TextView title=label(heading,11,false,muted());
        headingRow.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        View dot=new View(this);dot.setBackground(shape(color,10,false));headingRow.addView(dot,new LinearLayout.LayoutParams(dp(7),dp(7)));
        c.addView(headingRow);gap(c,9);c.addView(label(value,27,true,primary()));gap(c,6);
        c.addView(label(foot,11,false,muted()));c.setOnClickListener(v->click.run());
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(123),1);
        lp.setMargins(dp(0),dp(0),dp(9),dp(10));row.addView(c,lp);
    }
    private void switcher(LinearLayout parent,String left,String right,boolean leftActive,Runnable leftClick,Runnable rightClick) {
        LinearLayout row=horizontal();row.setBackground(shape(surface(),13,true));pad(row,3);
        Button a=button(left,leftClick,leftActive),b=button(right,rightClick,!leftActive);
        row.addView(a,weight(46));row.addView(b,weight(46));parent.addView(row);gap(parent,15);
    }
    private void pager(LinearLayout parent,String date,Runnable previous,Runnable next,Runnable today) {
        LinearLayout row=horizontal();row.addView(button("‹",previous,false),new LinearLayout.LayoutParams(dp(48),dp(46)));
        TextView title=label(date,14,true,primary());title.setGravity(Gravity.CENTER);
        row.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        row.addView(button("›",next,false),new LinearLayout.LayoutParams(dp(48),dp(46)));
        parent.addView(row);
        if(today!=null){gap(parent,12);Button t=button("Сегодня",today,false);
            parent.addView(t,new LinearLayout.LayoutParams(-1,dp(46)));}
        gap(parent,18);
    }
    private void schedule() {
        switcher(content,"День","Неделя",!showWeek,
            ()->{showWeek=false;selectedDate=LocalDate.now();loadData();},
            ()->{showWeek=true;selectedDate=JournalDisplay.startingWeekDate(LocalDate.now());loadData();});
        pager(content,showWeek?"Неделя от "+selectedDate.with(DayOfWeek.MONDAY).format(dateFormat):selectedDate.format(dateFormat),
            ()->shiftSchedule(-1),()->shiftSchedule(1),()->{
                selectedDate=showWeek?JournalDisplay.startingWeekDate(LocalDate.now()):LocalDate.now();loadData();
            });
        if(data==null||(!data.date().equals(selectedDate)||data.week()!=showWeek)&&fetching){
            secondary(content,"Загружаем расписание выбранного периода...");return;
        }
        List<Lesson> lessons=new ArrayList<>(data.lessons());
        lessons.sort(Comparator.comparing(Lesson::date).thenComparing(Lesson::start));
        if(lessons.isEmpty()){secondary(content,"Занятий нет");return;}
        LocalDate previous=null;
        for(Lesson l:lessons){
            if(!l.date().equals(previous)){gap(content,9);txt(content,l.date().format(dateFormat),17,true);gap(content,10);previous=l.date();}
            LinearLayout p=card();txt(p,l.subject(),18,true);secondary(p,l.start()+"–"+l.end());
            if(l.teacher()!=null&&!l.teacher().isBlank())secondary(p,l.teacher());
            if(l.room()!=null&&!l.room().isBlank())secondary(p,l.room());content.addView(p);
        }
    }
    private void shiftSchedule(int dir) {selectedDate=selectedDate.plusDays(showWeek?7L*dir:dir);loadData();}
    private void homework() {
        HorizontalScrollView sc=new HorizontalScrollView(this);sc.setHorizontalScrollBarEnabled(false);
        LinearLayout items=horizontal();String[] titles={"Все","Активно","Просрочено","На проверке","Проверено"};
        HomeworkStatus[] states={null,HomeworkStatus.pending,HomeworkStatus.overdue,HomeworkStatus.review,HomeworkStatus.done};
        for(int i=0;i<titles.length;i++){
            HomeworkStatus filter=states[i];Button btn=button(titles[i],()->{homeworkFilter=filter;homeworkScroll=0;showPage();},
                Objects.equals(homeworkFilter,filter));
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,dp(42));lp.rightMargin=dp(7);items.addView(btn,lp);
        }
        sc.addView(items);content.addView(sc);gap(content,11);
        Spinner sort=new Spinner(this);String[] labels={"По дедлайну ↑","По дедлайну ↓","По предметам"};
        ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,labels);
        sort.setAdapter(adapter);sort.setSelection(homeworkSort.ordinal());
        sort.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
            @Override public void onItemSelected(android.widget.AdapterView<?> parent,View view,int position,long id){
                JournalFilters.HomeworkSort wanted=JournalFilters.HomeworkSort.values()[position];
                if(wanted!=homeworkSort){homeworkSort=wanted;homeworkScroll=0;showPage();}
            }
        });content.addView(sort,new LinearLayout.LayoutParams(-1,dp(45)));gap(content,12);
        List<Homework> chosen=data.homework().stream().filter(h->homeworkFilter==null||h.status()==homeworkFilter).collect(Collectors.toList());
        Map<HomeworkStatus,List<Homework>> groups=JournalFilters.groupedHomework(chosen,homeworkSort);
        if(groups.isEmpty())secondary(content,"Заданий нет");
        for(Map.Entry<HomeworkStatus,List<Homework>> group:groups.entrySet()){
            sectionTitle(content,group.getKey().label()+" · "+group.getValue().size(),null,"");
            if(homeworkSort==JournalFilters.HomeworkSort.subject){
                for(Map.Entry<String,List<Homework>> subject:JournalFilters.groupedBySubject(group.getValue()).entrySet()){
                    txt(content,subject.getKey(),14,true);gap(content,8);
                    for(Homework h:subject.getValue())homeworkCard(h);
                }
            }else for(Homework h:group.getValue())homeworkCard(h);
        }
    }
    private void homeworkCard(Homework h) {
        LinearLayout p=card();LinearLayout row=horizontal();
        TextView subject=label(h.subject(),12,true,cyan);row.addView(subject,new LinearLayout.LayoutParams(0,-2,1));
        row.addView(label(h.status().label(),11,true,statusColor(h.status())));
        p.addView(row);gap(p,9);txt(p,h.title(),16,true);
        secondary(p,"Сдать: "+(h.due()==null?"—":h.due().format(dateFormat)));
        p.setOnClickListener(v->openHomework(h));content.addView(p);
    }
    private int statusColor(HomeworkStatus status){return switch(status){
        case pending -> green; case overdue -> red; case review -> accent; case done, deleted -> muted();
    };}
    private void openHomework(Homework h) {
        if(pageScroll!=null&&"Задания".equals(currentPage))homeworkScroll=pageScroll.getScrollY();
        selectedHomework=h;selectedUpload=null;uploadFilename="";draftAnswer="";moreOpen=false;
        showHomeworkDetail();
    }
    private void showHomeworkDetail() {
        Homework h=selectedHomework;if(h==null){showPage();return;}
        root=vertical();root.setBackgroundColor(background());
        LinearLayout header=horizontal();header.setPadding(dp(12),dp(12),dp(16),dp(12));header.setBackgroundColor(surface());
        Button back=button("‹",this::closeHomework,false);header.addView(back,new LinearLayout.LayoutParams(dp(48),dp(48)));
        LinearLayout heading=vertical();heading.setPadding(dp(10),0,0,0);
        heading.addView(label(h.title(),17,true,primary()));heading.addView(label(h.subject(),12,false,muted()));
        header.addView(heading,new LinearLayout.LayoutParams(0,-2,1));root.addView(header);
        ScrollView sc=new ScrollView(this);root.addView(sc,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout body=vertical();pad(body,16);sc.addView(body);
        LinearLayout description=card();
        secondary(description,"Сдать: "+(h.due()==null?"—":h.due().format(dateFormat)));
        if(h.grade()!=null)secondary(description,"Оценка: "+h.grade());
        secondary(description,"Статус: "+h.status().label());gap(description,11);
        txt(description,"Описание задания",17,true);gap(description,8);
        secondary(description,h.description()==null||h.description().isBlank()?"Описание не указано":h.description());
        body.addView(description);
        LinearLayout materials=card();txt(materials,"Материалы преподавателя",17,true);gap(materials,9);
        if(h.teacherFileUrl()!=null&&!h.teacherFileUrl().isBlank())
            materials.addView(button("↓  "+fileTitle(h.teacherFileName()),()->download(h.teacherFileUrl(),h.teacherFileName()),false),new LinearLayout.LayoutParams(-1,dp(50)));
        else secondary(materials,"Нет прикреплённых файлов");body.addView(materials);
        if(h.teacherComment()!=null&&!h.teacherComment().isBlank()){
            LinearLayout note=card();txt(note,"Комментарий преподавателя",17,true);
            secondary(note,h.teacherComment());body.addView(note);
        }
        if(h.status()==HomeworkStatus.pending||h.status()==HomeworkStatus.overdue){
            LinearLayout answer=card();txt(answer,"Мой ответ",17,true);gap(answer,12);
            EditText text=field("Текстовый ответ",false);text.setSingleLine(false);text.setMinLines(3);
            text.setText(draftAnswer);
            text.addTextChangedListener(new TextWatcher(){
                @Override public void beforeTextChanged(CharSequence s,int start,int count,int after){}
                @Override public void onTextChanged(CharSequence s,int start,int before,int count){draftAnswer=s.toString();}
                @Override public void afterTextChanged(Editable e){}
            });
            answer.addView(text);gap(answer,12);
            Button file=button(uploadFilename.isBlank()?"Выбрать файл":uploadFilename,
                ()->{Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);intent.setType("*/*");
                    startActivityForResult(intent,chooseUploadRequest);},false);
            answer.addView(file,new LinearLayout.LayoutParams(-1,dp(48)));gap(answer,12);
            TextView status=label("Отправка станет доступна после подтверждения API.",12,false,muted());answer.addView(status);
            Button send=button("Отправить на проверку",null,true);send.setEnabled(false);
            answer.addView(send,new LinearLayout.LayoutParams(-1,dp(48)));body.addView(answer);
        }else{
            LinearLayout sent=card();txt(sent,"Моя отправленная работа",17,true);gap(sent,10);
            if(h.studentFileUrl()!=null&&!h.studentFileUrl().isBlank()){
                sent.addView(button("↓  "+fileTitle(h.studentFileName()),()->download(h.studentFileUrl(),h.studentFileName()),false),
                    new LinearLayout.LayoutParams(-1,dp(48)));
            }else secondary(sent,"Файл не найден в ответе сервера");
            if(h.studentAnswer()!=null&&!h.studentAnswer().isBlank())secondary(sent,h.studentAnswer());
            gap(sent,12);
            Button operation=button(h.status()==HomeworkStatus.review?"Удалить работу":"Запрос на пересдачу",null,false);
            operation.setEnabled(false);sent.addView(operation,new LinearLayout.LayoutParams(-1,dp(48)));
            secondary(sent,"Операция ожидает подтверждения API.");body.addView(sent);
        }
        gap(body,24);setupFrame(root);
    }
    private String fileTitle(String value){return value==null||value.isBlank()?"Вложение":value;}
    private void closeHomework(){selectedHomework=null;selectedUpload=null;uploadFilename="";draftAnswer="";showPage();}
    @Override public void onBackPressed() {
        if(selectedHomework!=null){closeHomework();return;}
        if(moreOpen){moreOpen=false;showPage();return;}
        if(!"Главная".equals(currentPage)){navigate("Главная");return;}
        super.onBackPressed();
    }
    private void download(String link,String name) {
        toast("Скачиваем файл...");
        work(()->{Path dir=getCacheDir().toPath().resolve("attachments");Files.createDirectories(dir);
            return source.downloadAttachmentToDirectory(link,dir,name);},path->{downloadedFile=path;
            Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT);intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/octet-stream");intent.putExtra(Intent.EXTRA_TITLE,path.getFileName().toString());
            startActivityForResult(intent,saveDocumentRequest);
        },error->new AlertDialog.Builder(this).setTitle("Не удалось скачать файл")
            .setMessage(safeError(error)).setPositiveButton("OK",null).show());
    }
    @Override protected void onActivityResult(int request,int result,Intent response) {
        super.onActivityResult(request,result,response);
        if(result!=RESULT_OK||response==null||response.getData()==null)return;
        Uri uri=response.getData();
        if(request==chooseUploadRequest){
            selectedUpload=uri;
            uploadFilename="Файл выбран";
            try(android.database.Cursor c=getContentResolver().query(uri,null,null,null,null)){
                if(c!=null&&c.moveToFirst()){
                    int col=c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                    if(col>=0)uploadFilename=c.getString(col);
                }
            }catch(Exception ignored){}
            showHomeworkDetail();
        }else if(request==saveDocumentRequest&&downloadedFile!=null){
            Path path=downloadedFile;
            work(()->{try(var input=Files.newInputStream(path);var output=getContentResolver().openOutputStream(uri)){
                if(output==null)throw new java.io.IOException("Нет доступа к документу");input.transferTo(output);
            }finally{Files.deleteIfExists(path);}return true;},ok->{downloadedFile=null;toast("Файл сохранён");},
                error->toast("Ошибка сохранения: "+safeError(error)));
        }
    }
    private void periodControls() {
        switcher(content,"Месяц","Учебный год",period==JournalFilters.Period.month,
            ()->{period=JournalFilters.Period.month;showPage();},
            ()->{period=JournalFilters.Period.academicYear;showPage();});
        String title=period==JournalFilters.Period.month?periodDate.format(monthFormat):
            JournalFilters.periodStart(periodDate,period).getYear()+"–"+(JournalFilters.periodStart(periodDate,period).getYear()+1);
        pager(content,title,()->shiftPeriod(-1),()->shiftPeriod(1),null);
    }
    private void shiftPeriod(int dir){periodDate=JournalFilters.shift(periodDate,period,dir);showPage();}
    private void grades() {
        periodControls();List<Grade> rows=JournalFilters.grades(data.grades(),periodDate,period);
        double average=rows.stream().mapToInt(Grade::value).average().orElse(0);
        LinearLayout head=card();txt(head,String.format(russian,"Средний балл: %.2f",average),20,true);
        secondary(head,"Всего оценок: "+rows.size());content.addView(head);
        if(rows.isEmpty())secondary(content,"За выбранный период оценок нет");
        for(Grade grade:rows){LinearLayout item=card();txt(item,grade.subject()+" · "+grade.value(),17,true);
            secondary(item,grade.type());if(grade.theme()!=null&&!grade.theme().isBlank())secondary(item,grade.theme());
            if(grade.date()!=null)secondary(item,grade.date().format(dateFormat));content.addView(item);}
    }
    private void attendance() {
        periodControls();List<Attendance> rows=JournalFilters.attendance(data.attendanceEntries(),periodDate,period);
        if(data.attendanceEntries().isEmpty())secondary(content,"Нет датированных записей посещаемости.");
        if(rows.isEmpty())secondary(content,"За выбранный период нет записей");
        for(Attendance a:rows){LinearLayout p=card();txt(p,a.subject(),17,true);
            secondary(p,a.visited()+" из "+a.total()+" · "+Math.round(a.percent())+"%");
            ProgressBar bar=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);
            bar.setMax(100);bar.setProgress((int)Math.round(a.percent()));
            bar.setProgressTintList(ColorStateList.valueOf(green));
            bar.setProgressBackgroundTintList(ColorStateList.valueOf(surface()));
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(6));lp.topMargin=dp(12);p.addView(bar,lp);content.addView(p);}
    }
    private void calendar() {
        pager(content,calendarMonth.format(monthFormat),()->{calendarMonth=calendarMonth.minusMonths(1);calendarDay=calendarMonth.atDay(1);showPage();},
            ()->{calendarMonth=calendarMonth.plusMonths(1);calendarDay=calendarMonth.atDay(1);showPage();},
            ()->{calendarMonth=YearMonth.now();calendarDay=LocalDate.now();showPage();});
        LinearLayout legend=horizontal();
        legend.addView(label("● Был   ",11,false,green));
        legend.addView(label("● Опоздал   ",11,false,amber));
        legend.addView(label("● Пропустил",11,false,red));content.addView(legend);gap(content,14);
        LinearLayout month=card();String[] weekdays={"Пн","Вт","Ср","Чт","Пт","Сб","Вс"};
        LinearLayout header=horizontal();for(String d:weekdays){TextView t=label(d,11,true,muted());t.setGravity(Gravity.CENTER);
            header.addView(t,weight(30));}month.addView(header);
        List<JournalCalendar.Day> days=JournalCalendar.forMonth(data,calendarMonth);
        int offset=calendarMonth.atDay(1).getDayOfWeek().getValue()-1;
        int cells=((offset+calendarMonth.lengthOfMonth()+6)/7)*7;
        for(int start=0;start<cells;start+=7){
            LinearLayout week=horizontal();
            for(int col=0;col<7;col++){
                int dayNo=start+col-offset+1;
                if(dayNo<1||dayNo>calendarMonth.lengthOfMonth()){
                    week.addView(new View(this),weight(55));continue;
                }
                JournalCalendar.Day day=days.get(dayNo-1);
                LinearLayout tile=vertical();tile.setGravity(Gravity.CENTER);
                boolean chosen=day.date().equals(calendarDay);
                tile.setBackground(shape(chosen?(dark?Color.rgb(59,88,135):Color.rgb(207,224,250)):surface(),8,chosen));
                tile.addView(label(String.valueOf(dayNo),13,chosen,primary()));
                LinearLayout marks=horizontal();marks.setGravity(Gravity.CENTER);
                for(AttendanceState state:new AttendanceState[]{AttendanceState.present,AttendanceState.late,AttendanceState.absent})
                    if(day.hasAttendance(state)){
                        View dot=new View(this);dot.setBackground(shape(calendarColor(state),9,false));
                        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(dp(5),dp(5));lp.setMargins(dp(1),0,dp(1),0);marks.addView(dot,lp);
                    }
                tile.addView(marks);tile.setOnClickListener(v->{calendarDay=day.date();showCalendarDay(day);});
                LinearLayout.LayoutParams lp=weight(55);lp.setMargins(dp(1),dp(2),dp(1),dp(2));week.addView(tile,lp);
            }
            month.addView(week);
        }
        content.addView(month);
        gap(content,8);
        secondary(content,"Нажми на день, чтобы открыть все занятия, оценки и посещаемость.");
    }
    private void showCalendarDay(JournalCalendar.Day day) {
        Dialog dialog = new Dialog(this);
        LinearLayout sheet = vertical();
        sheet.setBackground(shape(surface(),22,true));sheet.setPadding(dp(20),dp(20),dp(20),dp(24));
        LinearLayout heading = horizontal();
        TextView title = label(day.date().format(dateFormat),19,true,primary());
        heading.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        heading.addView(button("Закрыть",dialog::dismiss,false),new LinearLayout.LayoutParams(-2,dp(42)));
        sheet.addView(heading);gap(sheet,15);
        ScrollView scroll = new ScrollView(this);
        LinearLayout events = vertical();
        if(day.events().isEmpty())secondary(events,"На выбранный день записей нет.");
        for(JournalCalendar.LessonEvent event:day.events()){
            LinearLayout entry=vertical();entry.setBackground(shape(cardColor(),13,true));pad(entry,13);
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=dp(10);
            TextView subject=label(event.subject(),16,true,primary());entry.addView(subject);
            if(!event.time().isBlank())secondary(entry,event.time());
            if(event.attendance()!=AttendanceState.unknown){
                TextView state=label(event.attendance().label(),13,true,calendarColor(event.attendance()));
                entry.addView(state);
            }
            for(Grade grade:event.grades()){
                TextView mark=label(grade.type()+": "+grade.value(),13,true,accent);
                entry.addView(mark);
            }
            events.addView(entry,lp);
        }
        scroll.addView(events);
        // A bottom sheet must not extend beyond the phone viewport on busy days.
        int viewport = getResources().getDisplayMetrics().heightPixels;
        int required = Math.max(dp(70), day.events().size()*dp(110));
        sheet.addView(scroll,new LinearLayout.LayoutParams(-1,Math.min(Math.min(dp(440),viewport*3/5),required)));
        dialog.setContentView(sheet);
        android.view.Window window=dialog.getWindow();
        if(window!=null){
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setDimAmount(0.48f);
        }
        dialog.show();
        window=dialog.getWindow();
        if(window!=null){
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);
            window.setGravity(Gravity.BOTTOM);
        }
    }
    private int calendarColor(AttendanceState status) {return switch(status){
        case present -> green;case late -> amber;case absent -> red;case unknown -> muted();
    };}
    private void profile() {
        LinearLayout info=card();txt(info,data.profile().name(),22,true);secondary(info,data.profile().group());content.addView(info);
        Button theme=button(dark?"Светлая тема":"Тёмная тема",()->{
            dark=!dark;getWindow().setStatusBarColor(background());getWindow().setNavigationBarColor(background());showPage();
        },false);content.addView(theme,new LinearLayout.LayoutParams(-1,dp(48)));gap(content,13);
        content.addView(button("Выйти из аккаунта",()->new AlertDialog.Builder(this)
            .setTitle("Выйти из аккаунта?").setNegativeButton("Отмена",null)
            .setPositiveButton("Выйти",(d,w)->{sessions.clear();source=null;data=null;showLogin(null);}).show(),false),
            new LinearLayout.LayoutParams(-1,dp(48)));
        gap(content,22);TextView version=label("JavaJournal · "+BuildConfig.VERSION_NAME,12,false,muted());
        version.setGravity(Gravity.RIGHT);content.addView(version);
    }
    private void settings() {
        LinearLayout p=card();txt(p,"Настройки",20,true);gap(p,10);
        p.addView(button(dark?"Светлая тема":"Тёмная тема",()->{
            dark=!dark;getWindow().setStatusBarColor(background());getWindow().setNavigationBarColor(background());showPage();
        },false),new LinearLayout.LayoutParams(-1,dp(48)));gap(p,12);
        p.addView(button("Обновить данные",this::refresh,false),new LinearLayout.LayoutParams(-1,dp(48)));
        content.addView(p);
    }
    @Override protected void onDestroy() {executor.shutdownNow();super.onDestroy();}
}
