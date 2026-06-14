package tormozit;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.swt.widgets.Display;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.text.TextSelection;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.jface.viewers.ISelectionProvider;
import org.eclipse.ui.IEditorInput;
import org.eclipse.text.undo.DocumentUndoManagerRegistry;
import org.eclipse.text.undo.IDocumentUndoManager;
import org.eclipse.xtext.ui.editor.model.IXtextDocument;
import org.eclipse.xtext.util.TextRegion;

import com._1c.g5.v8.dt.bsl.ui.editor.BslXtextEditor;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;

public final class IRSession
    {
        public final IRApplication.State state;
        public final LocalDateTime startTime;
        public final long pid;
        public final String platformVersion;
        final Object root; // V8X.Application
        final Object processObj; // WMIProcess
        public String appTitle;
        public org.eclipse.core.resources.IProject project;
        public final ExecutorService executor; // Выделенный поток для всех операций с этой COM-сессией
        /** Не null, если ИР подключён портативно (ирПортативный.epf), а не через расширение.
         *  В этом случае getModule() использует эту форму вместо root (COM-приложения). */
        public Object moduleRoot = null;
        public InfobaseReference infobase;
        public Object codeEditor = null; // ирКлсПолеТекстаПрограммы
        public TextRegion changedTextRange = null;
        public String newTextOfRange = "";

        /** project-relative путь .bsl → hash содержимого на момент последнего setText в ИР. */
        private final Map<String, byte[]> pushedSignatures = new ConcurrentHashMap<>();

        /** Последний модуль и текст, ушедший в ИР через {@link #setText} (для обратного remap диапазона). */
        private String lastSyncedModuleName = ""; //$NON-NLS-1$
        /** {@code doc.get()} на момент sync — координаты для {@link #syncCodeEditorFromIR}. */
        private String lastSyncedRawText = ""; //$NON-NLS-1$
        private String lastSyncedLfText = ""; //$NON-NLS-1$

        /** Кэш HWND главного окна ИР (native value) для повторных modal-сессий. */
        private volatile long cachedIrMainHwnd;

        IRSession(IRApplication.State state, LocalDateTime startTime, long pid, String platformVersion,
                  Object root, Object processObj, String appTitle, org.eclipse.core.resources.IProject project,
                  ExecutorService executor, InfobaseReference infobase)
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

        boolean isAlreadyPushed(String bslPath, byte[] hash)
        {
            if (bslPath == null || hash == null)
                return false;
            byte[] prev = pushedSignatures.get(bslPath);
            return prev != null && Arrays.equals(prev, hash);
        }

        void markPushed(String bslPath, byte[] hash)
        {
            if (bslPath == null || hash == null)
                return;
            pushedSignatures.put(bslPath, hash.clone());
        }

        void resetPushedSignatures()
        {
            pushedSignatures.clear();
        }

        /** Кэшированный HWND главного окна ИР (для повторных modal-сессий). */
        com.sun.jna.platform.win32.WinDef.HWND getCachedIrMainHwnd()
        {
            if (cachedIrMainHwnd == 0 || pid <= 0)
                return null;
            com.sun.jna.platform.win32.WinDef.HWND hwnd =
                WinWindowActivator.hwndFromNative(cachedIrMainHwnd);
            if (hwnd == null || !WinWindowActivator.isProcessWindow(hwnd, (int) pid))
            {
                cachedIrMainHwnd = 0;
                return null;
            }
            return hwnd;
        }

        void cacheIrMainHwnd(com.sun.jna.platform.win32.WinDef.HWND hwnd)
        {
            if (WinWindowActivator.isNullHwnd(hwnd))
                return;
            cachedIrMainHwnd = com.sun.jna.Pointer.nativeValue(hwnd.getPointer());
        }

        public Object getModule(String name)
        {
            return ComBridge.getProperty(moduleRoot != null ? moduleRoot : root, name);
        }
        public <T> T executeOnComThread(Callable<T> task) {
            if (state != IRApplication.State.CONNECTED)
            {
                IRApplication.notifyWaitForIrConnection();
                throw new IllegalStateException();
            }
            if (executor == null || executor.isShutdown()) {
                throw new IllegalStateException("COM-executor не инициализирован или остановлен");
            }
            try {
                return executor.submit(task).get(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                RuntimeException toThrow = new RuntimeException("Прервано ожидание COM-потока", e); //$NON-NLS-1$
                notifyComThreadError(toThrow);
                throw toThrow;
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                RuntimeException toThrow;
                if (cause instanceof RuntimeException)
                    toThrow = (RuntimeException) cause;
                else
                    toThrow = new RuntimeException("Ошибка в COM-потоке", cause); //$NON-NLS-1$
                notifyComThreadError(toThrow);
                throw toThrow;
            } catch (TimeoutException e) {
                RuntimeException toThrow = new RuntimeException("Таймаут ожидания COM-потока (10 сек)", e); //$NON-NLS-1$
                notifyComThreadError(toThrow);
                throw toThrow;
            }
        }

        private static void notifyComThreadError(Throwable cause)
        {
            String detail = cause != null ? cause.getMessage() : null;
            if (detail == null || detail.isEmpty())
                detail = cause != null ? cause.toString() : ""; //$NON-NLS-1$
            detail = ComBridge.formatErrorForNotification(detail);
            final String message = "Ошибка вызова ИР: " + detail; //$NON-NLS-1$
            Display display = Display.getDefault();
            if (display != null && !display.isDisposed())
                display.asyncExec(() -> ToastNotification.show(IRApplication.toastTitle(), message, 5_000));
        }

        public boolean isProcessAlive() {
            if (pid <= 0) return true; // pid неизвестен — не блокируем
            return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
        }

        public void syncCodeEditorToIR(BslXtextEditor editor) {
            ISourceViewer viewer = editor.getInternalSourceViewer();
            Object sel = viewer.getSelectionProvider().getSelection();
            ITextSelection textSelection = (ITextSelection) sel;
            IXtextDocument doc = (IXtextDocument) viewer.getDocument();
            final String currentModuleName = GetRef.resolveSetTextModuleName(editor);
            final String text       = doc.get();
            final int offset        = textSelection.getOffset();
            final int endOffset     = offset + textSelection.getLength();

            String currentBslPath = ""; //$NON-NLS-1$
            byte[] currentHash = null;
            IEditorInput input = editor.getEditorInput();
            if (input != null)
            {
                IFile file = input.getAdapter(IFile.class);
                if (file != null)
                {
                    currentBslPath = file.getProjectRelativePath().toString().replace('\\', '/');
                    try
                    {
                        currentHash = IRModuleChangeCollector.contentHash(file);
                    }
                    catch (CoreException ignored) {}
                }
            }

            final List<IRModuleChangeCollector.ModuleSyncEntry> pending =
                IRModuleChangeCollector.collectPendingModules(
                    this, project, infobase, currentModuleName);
            final String bslPath = currentBslPath;
            final byte[] hash = currentHash;

            executeOnComThread(() -> {
                ensureCodeEditor();
                for (IRModuleChangeCollector.ModuleSyncEntry e : pending)
                {
                    setText(e.text, e.moduleName, 0, 0);
                    markPushed(e.bslPath, e.hash);
                    IRModuleSyncDebug.logPushed(e.moduleName, e.bslPath);
                }
                setText(text, currentModuleName, offset, endOffset);
                if (!bslPath.isEmpty() && hash != null)
                    markPushed(bslPath, hash);
                return null;
            });
        }

        private void ensureCodeEditor()
        {
            if (codeEditor == null)
            {
                Object irCache = getModule("ирКэш"); //$NON-NLS-1$
                codeEditor = ComBridge.invoke(irCache, "ПолеТекстаПрограммы", 0); //$NON-NLS-1$
            }
        }

        /** Синхронизация текста «Редактора запроса» в поле ИР (язык запросов). */
        public void syncQueryEditorToIR(ISourceViewer viewer)
        {
            if (viewer == null)
                return;

            IDocument doc = viewer.getDocument();
            if (doc == null)
                return;

            Object sel = viewer.getSelectionProvider().getSelection();
            ITextSelection textSelection = sel instanceof ITextSelection ts ? ts : new TextSelection(0, 0);
            final String text = doc.get();
            final int offset = textSelection.getOffset();
            final int endOffset = offset + textSelection.getLength();

            executeOnComThread(() -> {
                ensureCodeEditor();
                ComBridge.setProperty(codeEditor, "ЯзыкПрограммы", 1); //$NON-NLS-1$
                setText(text, "", offset, endOffset); //$NON-NLS-1$
                return null;
            });
        }

        // порт ПередатьИзмененияИзПоляТекстаВОкноМодуля + ПередатьГраницыВыделенияИзПолеТекстаПрограммы
        public void syncCodeEditorFromIR(BslXtextEditor editor)
        {
            if (editor == null)
                return;
            syncTextEditorFromIR(editor.getInternalSourceViewer(), 0);
            TextEditorSupport.focusBslEditor(editor);
        }

        /** Возврат правок из поля ИР в произвольный {@link ISourceViewer} (BSL, запрос и т.п.). */
        public void syncTextEditorFromIR(ISourceViewer viewer, int endOffsetAdjustment)
        {
            if (viewer == null)
                return;

            boolean hasReplace = Boolean.TRUE.equals(executeOnComThread(this::hasReplaceableRange));
            if (hasReplace)
            {
                executeOnComThread(() -> {
                    readChangedTextRange();
                    return null;
                });
                IDocument doc = viewer.getDocument();
                if (doc == null)
                    return;

                int offsetAdjust = TextEditorSupport.saveSelectionBoundsForUndo(viewer);
                final int replaceOffset = changedTextRange.getOffset() + offsetAdjust;
                final int replaceLength = changedTextRange.getLength();
                final String insertText = newTextOfRange;

                if (doc instanceof IXtextDocument xtextDoc)
                {
                    xtextDoc.modify(resource -> {
                        try
                        {
                            xtextDoc.replace(replaceOffset, replaceLength, insertText);
                        }
                        catch (BadLocationException e)
                        {
                            throw new RuntimeException("Ошибка позиционирования при вставке текста из ИР", e); //$NON-NLS-1$
                        }
                        return null;
                    });
                }
                else
                {
                    try
                    {
                        doc.replace(replaceOffset, replaceLength, insertText);
                    }
                    catch (BadLocationException e)
                    {
                        throw new RuntimeException("Ошибка позиционирования при вставке текста из ИР", e); //$NON-NLS-1$
                    }
                }

                IDocumentUndoManager undoManager = DocumentUndoManagerRegistry.getDocumentUndoManager(doc);
                if (undoManager != null)
                    undoManager.commit();
                changedTextRange = null;
                newTextOfRange = ""; //$NON-NLS-1$
            }
            syncSelectionFromIR(viewer, endOffsetAdjustment);
        }

        public void syncQueryEditorFromIR(ISourceViewer viewer)
        {
            syncTextEditorFromIR(viewer, 0);
            TextEditorSupport.focusSourceViewer(viewer);
        }

        /** Порт ПередатьГраницыВыделенияИзПолеТекстаПрограммы. */
        public void syncSelectionFromIR(BslXtextEditor editor)
        {
            syncSelectionFromIR(editor, 0);
        }

        public void syncSelectionFromIR(BslXtextEditor editor, int endOffsetAdjustment)
        {
            if (editor == null)
                return;
            syncSelectionFromIR(editor.getInternalSourceViewer(), endOffsetAdjustment);
        }

        public void syncSelectionFromIR(ISourceViewer viewer, int endOffsetAdjustment)
        {
            if (viewer == null)
                return;
            IDocument doc = viewer.getDocument();
            if (doc == null)
                return;

            final int adj = endOffsetAdjustment;
            int[] lfSel = executeOnComThread(() -> readIrSelectionLf(adj));
            if (lfSel == null)
                return;

            String raw = doc.get();
            int docStart = Global.remapOffsetFromLf(raw, lfSel[0]);
            int docEnd = Global.remapOffsetFromLf(raw, lfSel[1]);
            IRModuleSyncDebug.logSelectionFromIr(lfSel[0], lfSel[1], docStart, docEnd);
            viewer.setSelectedRange(docStart, Math.max(0, docEnd - docStart));
        }
        /**
         * 
         */
        public void openTextEditor(String text, String sourceRef)
        {
            Object irClient = getModule("ирКлиент"); //$NON-NLS-1$
            String lfText = Global.normalizeLineSeparators(text != null ? text : ""); //$NON-NLS-1$
            // (Текст, Знач Заголовок = "", ВариантПросмотра = "Компактный", ТолькоПросмотр = Ложь, Знач КлючУникальности = Неопределено, ВладелецФормы = Неопределено, ВыделитьВсе = Ложь,
            // Знач Модально = Ложь, ВыделениеДвумерное = Неопределено, Знач ИскомаяСтрока = "", Знач КлючИсточника = "")
            ComBridge.invoke(irClient, "ОткрытьТекстЛкс", lfText, sourceRef, null, false, sourceRef, null, false, false, null, "", sourceRef); //$NON-NLS-1$                
        }
        
        public void setText(String text
            , String moduleName
            , int startOffset // from 0
            , int endOffset // from 0
        )
        {
            Global.LfTextSlice slice = Global.toLfWithSelection(text, startOffset, endOffset);
            if (moduleName != null && !moduleName.isEmpty() && slice.text() != null)
            {
                lastSyncedModuleName = moduleName;
                lastSyncedRawText = text != null ? text : ""; //$NON-NLS-1$
                lastSyncedLfText = slice.text();
            }
            IRModuleSyncDebug.logSetTextSelection(
                startOffset, endOffset, slice.start(), slice.end(), Global.countCrlf(text));
//            Процедура УстановитьТекст(Знач Текст = Неопределено, Знач Активировать = Ложь, Знач НачальныйТекстДляСравнения = Неопределено, Знач СохранитьГраницыВыделения = Ложь, Знач ИмяМодуляСжатое = Неопределено,
//                Знач ИмяМодуля = Неопределено, Знач НовоеНачалоВыделения = 0, Знач НовоеКонецВыделения = 0) Экспорт
            ComBridge.invoke(codeEditor, "УстановитьТекст", slice.text(), false, null, false, null, moduleName, slice.start() + 1,
                slice.end() + 1);
        }

        public Object replaceSelectedText(String text)
        {
            String lfText = Global.normalizeLineSeparators(text != null ? text : ""); //$NON-NLS-1$
     //        ВставитьИзмененныйТекстовыйЛитерал(Знач НовыйТекст, Знач СтарыйТекстЛитерала = "", выхТекстИзменен = Ложь)
            String string = ComBridge.toString(ComBridge.invoke(codeEditor, "ВставитьИзмененныйТекстовыйЛитерал", lfText));
            return string;
            
       }

        public void readChangedTextRange()
        {
            newTextOfRange = Global.normalizeLineSeparators(
                ComBridge.toString(ComBridge.getProperty(codeEditor, "мЗамещающийФрагмент"))); //$NON-NLS-1$
            Object comRange = ComBridge.getProperty(codeEditor, "мЗаменяемыйДиапазон"); //$NON-NLS-1$
            int irLfStart = (int) ComBridge.toLong(ComBridge.getProperty(comRange, "Начало")) - 1; //$NON-NLS-1$
            int irLfEnd = (int) ComBridge.toLong(ComBridge.getProperty(comRange, "Конец")) - 1; //$NON-NLS-1$
            String raw = lastSyncedRawText != null ? lastSyncedRawText : ""; //$NON-NLS-1$
            int rangeStart = Global.remapOffsetFromLf(raw, irLfStart);
            int rangeEnd = Global.remapOffsetFromLf(raw, irLfEnd);
            IRModuleSyncDebug.logRangeFromIr(irLfStart, irLfEnd, rangeStart, rangeEnd);
            changedTextRange = new TextRegion(rangeStart, rangeEnd - rangeStart);
        }

        /** {@code мЗаменяемыйДиапазон} задан. Вызывать только из COM-потока. */
        private boolean hasReplaceableRange()
        {
            ensureCodeEditor();
            Object comRange = ComBridge.getProperty(codeEditor, "мЗаменяемыйДиапазон"); //$NON-NLS-1$
            return comRange != null;
        }

        /** [lfStart, lfEndExclusive] из {@code ПолеТекста.ВыделениеОдномерное()} или {@code null}. COM-поток. */
        private int[] readIrSelectionLf(int endOffsetAdjustment)
        {
            ensureCodeEditor();
            Object fieldText = ComBridge.getProperty(codeEditor, "ПолеТекста"); //$NON-NLS-1$
            if (fieldText == null)
                return null;
            Object sel1d = ComBridge.invoke(fieldText, "ВыделениеОдномерное"); //$NON-NLS-1$
            if (sel1d == null)
                return null;
            int irLfStart = (int) ComBridge.toLong(ComBridge.getProperty(sel1d, "Начало")) - 1; //$NON-NLS-1$
            int irLfEnd = (int) ComBridge.toLong(ComBridge.getProperty(sel1d, "Конец")) - 1 + endOffsetAdjustment; //$NON-NLS-1$
            return new int[] { irLfStart, irLfEnd };
        }

        public String selectTextLiteral()
        {
//            Функция ВыделитьТекстовыйЛитерал(Знач ПолеТекстаЛ = Неопределено, выхНачальнаяПозиция0 = 0, выхКонечнаяПозиция0 = 0, Знач РазбиратьКонтекст = Истина, выхВыражение = "",
//                Знач РазрешитьПотерюКомментариев = Истина) Экспорт 
            return ComBridge.toString(ComBridge.invoke(codeEditor, "ВыделитьТекстовыйЛитерал", null, null, null, true, null, false));
        }

        // Модальный
        public boolean openTextLiteralEditor()
        {
            return ComBridge.toBoolean(ComBridge.invoke(codeEditor, "ОткрытьРедакторТекстовогоЛитерала", null, null, null, true, null, false));
        }

        public void showWindow()
        {
           ComBridge.setProperty(root, "Visible", true);
           if (pid > 0)
               WinWindowActivator.activateMainWindow(pid);
         }

        public void openJobConsole()
        {
            executeOnComThread(() -> {
                showWindow();
                ComBridge.invoke(getModule("ирКлиент"), "ОткрытьКонсольЗаданийЛкс", true); //$NON-NLS-1$ //$NON-NLS-2$
                return null;
            });
        }

        /**
         * Выполняет блокирующий COM-вызов ИР в псевдомодальном режиме EDT↔ИР.
         * Вызывать только из {@link #executor}.
         */
        public <T> T runIrModalDialog(Pattern titlePattern, long waitForDialogMs, Callable<T> action)
            throws Exception
        {
            if (state != IRApplication.State.CONNECTED)
                throw new IllegalStateException("ИР не подключён"); //$NON-NLS-1$

            if (!WinWindowActivator.isWindows() || pid <= 0)
            {
                IrModalWindowDebug.problem("runIrModalDialog без Win32 — прямой вызов"); //$NON-NLS-1$
                return action.call();
            }

            IrModalWindowSession modal = IrModalWindowSession.begin(this, titlePattern, waitForDialogMs);
            try
            {
                return action.call();
            }
            finally
            {
                modal.end();
            }
        }

    }
