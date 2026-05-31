import java.time.LocalDateTime;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.core.resources.IProject;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.text.TextSelection;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.xtext.ui.editor.model.IXtextDocument;

import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;

public final class IRSession
    {
        public final IRApplication.State state;
        public final LocalDateTime startTime;
        public final long pid;
        public final String platformVersion;
        final Object root;
        final Object processObj;
        public String appTitle;
        public IProject project;
        public final ExecutorService executor; // Выделенный поток для всех операций с этой COM-сессией
        /** Не null, если ИР подключён портативно (ирПортативный.epf), а не через расширение.
         *  В этом случае getModule() использует эту форму вместо root (COM-приложения). */
        public Object moduleRoot = null;
        public InfobaseReference infobase;
        public IRCodeEditor codeEditor = null;

        IRSession(IRApplication.State state, LocalDateTime startTime, long pid, String platformVersion,
                  Object root, Object processObj, String appTitle, IProject project, ExecutorService executor, InfobaseReference infobase)
        {
            this.state = state;
            this.startTime = startTime;
            this.pid = pid;
            this.platformVersion = platformVersion;
            this.root = root;
            this.processObj = processObj;
            this.appTitle = appTitle;
            this.project = project;
            this.executor = executor;
            this.infobase = infobase;
        }

        public Object getModule(String name)
        {
            return ComBridge.getProperty(moduleRoot != null ? moduleRoot : root, name);
        }
        public <T> T executeOnComThread(Callable<T> task) {
            if (executor == null || executor.isShutdown()) {
                throw new IllegalStateException("COM-executor не инициализирован или остановлен");
            }
            try {
                return executor.submit(task).get(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Прервано ожидание COM-потока", e);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof RuntimeException) throw (RuntimeException) cause;
                throw new RuntimeException("Ошибка в COM-потоке", cause);
            } catch (TimeoutException e) {
                throw new RuntimeException("Таймаут ожидания COM-потока (10 сек)", e);
            }
        }

        public IRCodeEditor getCodeEditor(BslXtextEditor editor) {
            // Читаем данные из UI-потока EDT — они нам понадобятся в COM-потоке
            ISourceViewer viewer = editor.getInternalSourceViewer();
            Object sel = viewer.getSelectionProvider().getSelection();
            ITextSelection textSelection = (ITextSelection) sel;
            IXtextDocument doc = (IXtextDocument) viewer.getDocument();
            final String moduleName = ""; // TODO: вычислить при необходимости
            final String text       = doc.get();
            final int offset        = textSelection.getOffset();
            final int endOffset     = offset + textSelection.getLength();

            // Инициализация и setText выполняются в COM-потоке, результат возвращается синхронно
            return executeOnComThread(() -> {
                if (codeEditor == null) {
                    Object irCache = getModule("ирКэш");
                    codeEditor = new IRCodeEditor(ComBridge.invoke(irCache, "ПолеТекстаПрограммы", 0));
                }
                codeEditor.setText(text, moduleName, offset, endOffset);
                return codeEditor;
            });
        }
        /**
         * 
         */
        public void openTextEditor(String text, String sourceRef)
        {
            Object irClient = getModule("ирКлиент"); //$NON-NLS-1$
            // (Текст, Знач Заголовок = "", ВариантПросмотра = "Компактный", ТолькоПросмотр = Ложь, Знач КлючУникальности = Неопределено, ВладелецФормы = Неопределено, ВыделитьВсе = Ложь,
            // Знач Модально = Ложь, ВыделениеДвумерное = Неопределено, Знач ИскомаяСтрока = "", Знач КлючИсточника = "")
            ComBridge.invoke(irClient, "ОткрытьТекстЛкс", text, sourceRef, null, false, sourceRef, null, false, false, null, "", sourceRef); //$NON-NLS-1$                
        }
    }
