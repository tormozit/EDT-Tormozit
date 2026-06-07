package tormozit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.layout.GridLayoutFactory;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.events.MenuAdapter;
import org.eclipse.swt.events.MenuEvent;
import org.eclipse.swt.events.MouseAdapter;
import org.eclipse.swt.events.MouseEvent;
import org.eclipse.swt.events.PaintEvent;
import org.eclipse.swt.events.PaintListener;
import org.eclipse.swt.events.ControlAdapter;
import org.eclipse.swt.events.ControlEvent;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.widgets.ScrollBar;
import org.eclipse.swt.custom.ScrolledComposite;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.custom.CCombo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Text;

/**
 * Собственный SWT-список свойств вместо LWT-палитры EDT.
 * Строится из {@code ITreeTransformation}, поддерживает фильтр, выделение, подсветку и copy-menu.
 */
final class PropertySheetComfortUi
{
    interface SessionAccessor
    {
        Object page();

        Composite deck();

        Control resolveNativeValueControl(String propertyName, Object valueVm);
    }
    private static final String INSTALLED_KEY = "tormozit.propertySheet.comfortUi"; //$NON-NLS-1$
    private static final int NAME_COLUMN_WIDTH = 140;
    private static final int ROW_HORIZONTAL_INSET = 8;
    private static final int VALUE_NAME_SPACING = 8;
    private static final int VERTICAL_SCROLL_GUTTER = 18;
    private static final String HIGHLIGHT_KEY = "tormozit.ps.comfortHighlight"; //$NON-NLS-1$
    private static final String SELECTED_KEY = "tormozit.ps.comfortSelected"; //$NON-NLS-1$

    private static final Map<Object, Session> SESSIONS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private PropertySheetComfortUi() {}

    static boolean isInstalled(Object page)
    {
        return page != null && SESSIONS.containsKey(page);
    }

    static boolean hasRows(Object page)
    {
        Session session = SESSIONS.get(page);
        return session != null && !session.rows.isEmpty()
                && session.content != null && !session.content.isDisposed();
    }

    static boolean install(Object page, SmartMatcher matcher)
    {
        if (page == null || isInstalled(page))
            return isInstalled(page);
        PropertySheetPaletteHost.Target target = PropertySheetPaletteHost.resolve(page);
        if (!target.isValid())
        {
            PropertySheetDebug.uiVerbose("comfortUi WAIT host=null via=" + target.via); //$NON-NLS-1$
            return false;
        }

        Composite host = target.host;
        Control nativeContent = target.nativeControl;
        if (nativeContent == null || nativeContent.isDisposed())
            return false;

        Object layoutData = nativeContent.getLayoutData();
        org.eclipse.swt.graphics.Rectangle nativeBounds = nativeContent.getBounds();

        // Отладочный CTabFolder с двумя вкладками: "Старая" и "Новая"
        CTabFolder tabFolder = new CTabFolder(host, SWT.BORDER | SWT.BOTTOM);
        tabFolder.setSimple(false);
        tabFolder.setTabHeight(20);
        if (layoutData != null)
            tabFolder.setLayoutData(layoutData);
        else
            GridDataFactory.fillDefaults().grab(true, true).applyTo(tabFolder);
        tabFolder.setBounds(nativeBounds);

        // Вкладка "Старая" — нативный LWT-контрол EDT
        CTabItem nativeTab = new CTabItem(tabFolder, SWT.NONE);
        nativeTab.setText("Старая"); //$NON-NLS-1$

        // nativeContent переносим в tabFolder
        if (!nativeContent.setParent(tabFolder))
        {
            tabFolder.dispose();
            PropertySheetDebug.problem("comfortUi FAIL native setParent"); //$NON-NLS-1$
            return false;
        }
        nativeTab.setControl(nativeContent);

        // Вкладка "Новая" — наш Comfort UI
        CTabItem comfortTab = new CTabItem(tabFolder, SWT.NONE);
        comfortTab.setText("Новая"); //$NON-NLS-1$

        org.eclipse.swt.custom.ScrolledComposite scrolled =
                new org.eclipse.swt.custom.ScrolledComposite(tabFolder, SWT.V_SCROLL);
        scrolled.setExpandHorizontal(true);
        scrolled.setExpandVertical(true);
        comfortTab.setControl(scrolled);

        Composite content = new Composite(scrolled, SWT.NONE);
        content.setLayout(GridLayoutFactory.fillDefaults().margins(0, 0).spacing(0, 0).create());
        GridDataFactory.fillDefaults().grab(true, true).applyTo(content);
        scrolled.setContent(content);

        // Показываем "Старую" пока нет данных
        tabFolder.setSelection(nativeTab);

        // Для совместимости с остальным кодом храним deck=tabFolder, nativeContent
        Composite deck = tabFolder; // CTabFolder extends Composite

        Session session = new Session(page, host, deck, nativeContent, scrolled, content, comfortTab, tabFolder);
        session.matcher = matcher != null ? matcher : new SmartMatcher(""); //$NON-NLS-1$
        tabFolder.addSelectionListener(new SelectionAdapter()
        {
            @Override
            public void widgetSelected(SelectionEvent e)
            {
                if (tabFolder.getSelection() == comfortTab)
                    relayoutContent(session);
            }
        });
        scrolled.addControlListener(new ControlAdapter()
        {
            @Override
            public void controlResized(ControlEvent e)
            {
                int width = resolveContentWidth(session);
                if (width == session.lastAppliedWidth)
                    return;
                relayoutContent(session);
            }
        });
        if (!rebuild(session, null))
        {
            nativeContent.setParent(host);
            nativeContent.setLayoutData(layoutData);
            nativeContent.setBounds(nativeBounds);
            tabFolder.dispose(); // dispose tabFolder вместо deck
            PropertySheetDebug.uiVerbose("comfortUi WAIT install data=0, native untouched"); //$NON-NLS-1$
            return false;
        }

        SESSIONS.put(page, session);
        Global.setField(page, INSTALLED_KEY, Boolean.TRUE);
        restoreSelection(session);
        applyHighlights(session);
        session.mirrorShown = true;
        relayoutContent(session);
        tabFolder.getDisplay().asyncExec(() -> relayoutContent(session));
        PropertySheetDebug.uiVerbose("comfortUi INSTALL via=" + target.via //$NON-NLS-1$
                + " host=" + PropertySheetDebug.safe(host) //$NON-NLS-1$
                + " native=" + PropertySheetDebug.safe(nativeContent)); //$NON-NLS-1$
        return true;
    }

    static boolean refresh(Object page, SmartMatcher matcher)
    {
        if (page == null)
            return false;
        Session session = SESSIONS.get(page);
        if (session == null || session.content.isDisposed())
            return false;
        SmartMatcher active = matcher != null ? matcher : new SmartMatcher(""); //$NON-NLS-1$
        session.matcher = active;
        Object transformation = PropertySheetViewModelTree.activeTransformation(session.page);
        List<PropertySheetViewModelTree.Entry> entries =
                PropertySheetViewModelTree.collect(session.page, transformation);
        String signature = entryStructureSignature(entries);
        String sourceKey = PropertySheetSourceKey.fingerprint(session.page);
        boolean sourceChanged = !sourceKey.equals(session.sourceKey);
        if (!entries.isEmpty() && !session.rows.isEmpty())
        {
            if (session.entrySignature.isEmpty())
                session.entrySignature = signature;
        }
        if (!entries.isEmpty() && signature.equals(session.entrySignature) && !session.rows.isEmpty())
        {
            if (sourceChanged)
            {
                session.sourceKey = sourceKey;
                invalidateLookupCaches(session);
            }
            updateRowValues(session, entries);
            restoreSelection(session);
            applyHighlights(session);
            if (isComfortTabSelected(session))
                relayoutContent(session);
            return true;
        }
        session.sourceKey = sourceKey;
        if (sourceChanged)
            invalidateLookupCaches(session);
        boolean hasRows = rebuild(session, entries);
        if (!hasRows && session.mirrorShown)
        {
            applyHighlights(session);
            return true;
        }
        if (!hasRows)
            return false;
        restoreSelection(session);
        applyHighlights(session);
        session.mirrorShown = true;
        relayoutContent(session);
        PropertySheetDebug.uiVerbose("comfortUi REFRESH rows=" + session.rows.size() //$NON-NLS-1$
                + " pattern=" + PropertySheetDebug.quote(active.fullPattern)); //$NON-NLS-1$
        return true;
    }

    static List<PropertySheetPaletteRow> rows(Object page)
    {
        Session session = SESSIONS.get(page);
        if (session == null)
            return Collections.emptyList();
        return new ArrayList<>(session.paletteRows);
    }

    static void disposeForPage(Object page)
    {
        Session session = SESSIONS.remove(page);
        if (session == null)
            return;
        // Возвращаем nativeContent обратно в host до удаления tabFolder
        if (session.nativeContent != null && !session.nativeContent.isDisposed()
                && session.host != null && !session.host.isDisposed())
        {
            Object layoutData = session.deck != null && !session.deck.isDisposed()
                    ? session.deck.getLayoutData() : null;
            org.eclipse.swt.graphics.Rectangle bounds = session.deck != null && !session.deck.isDisposed()
                    ? session.deck.getBounds() : session.nativeContent.getBounds();
            session.nativeContent.setParent(session.host);
            if (layoutData != null)
                session.nativeContent.setLayoutData(layoutData);
            session.nativeContent.setBounds(bounds);
            session.nativeContent.setVisible(true);
        }
        // tabFolder сам содержит scrolled и все CTabItem — disposeпом убиваем всё
        if (session.deck != null && !session.deck.isDisposed())
            session.deck.dispose();
        PropertySheetDebug.uiVerbose("comfortUi DISPOSE page=" + PropertySheetDebug.safe(page)); //$NON-NLS-1$
    }

    private static boolean rebuild(Session session, List<PropertySheetViewModelTree.Entry> entries)
    {
        if (entries == null)
        {
            Object transformation = PropertySheetViewModelTree.activeTransformation(session.page);
            entries = PropertySheetViewModelTree.collect(session.page, transformation);
        }
        PropertySheetDebug.uiVerbose("comfortUi data entries=" + entries.size()); //$NON-NLS-1$
        if (entries.isEmpty() && !session.rows.isEmpty())
        {
            PropertySheetDebug.uiVerbose("comfortUi KEEP previous rows=" + session.rows.size() //$NON-NLS-1$
                    + " because new entries=0"); //$NON-NLS-1$
            return true;
        }
        if (entries.isEmpty())
        {
            PropertySheetDebug.uiVerbose("comfortUi WAIT data entries=0, native stays visible"); //$NON-NLS-1$
            return false;
        }

        Composite oldContent = session.content;
        List<ComfortRow> oldRows = new ArrayList<>(session.rows);
        List<PropertySheetPaletteRow> oldPaletteRows = new ArrayList<>(session.paletteRows);

        Composite newContent = new Composite(session.scrolled, SWT.NONE);
        newContent.setLayout(GridLayoutFactory.fillDefaults().margins(0, 0).spacing(0, 0).create());
        GridDataFactory.fillDefaults().grab(true, true).applyTo(newContent);
        newContent.setVisible(false);

        session.content = newContent;
        session.rows.clear();
        session.paletteRows.clear();
        if (session.scannerRows.isEmpty())
            session.scannerRows = scannerRowsByName(session.page);
        Map<String, PropertySheetPaletteRow> nativeRows = nativeRowsByName(session, entries);
        int nativeValues = 0;
        int nonEmptyValues = 0;

        for (PropertySheetViewModelTree.Entry entry : entries)
        {
            if (entry.kind == PropertySheetViewModelTree.Kind.SECTION)
                session.rows.add(createSectionRow(session, entry));
            else
            {
                ComfortRow row = createPropertyRow(session, entry, nativeRows.get(entry.name));
                session.rows.add(row);
                if (row.nativeValueControl != null)
                    nativeValues++;
                if (row.valueText != null && !row.valueText.isEmpty())
                    nonEmptyValues++;
            }
        }

        if (session.rows.isEmpty())
        {
            session.content = oldContent;
            session.rows.clear();
            session.rows.addAll(oldRows);
            session.paletteRows.clear();
            session.paletteRows.addAll(oldPaletteRows);
            newContent.dispose();
            PropertySheetDebug.uiVerbose("comfortUi REBUILD abort keep previous rows=" + oldRows.size()); //$NON-NLS-1$
            return !oldRows.isEmpty();
        }

        applyContentSize(session, newContent);
        newContent.setVisible(true);
        session.scrolled.setContent(newContent);
        if (oldContent != null && !oldContent.isDisposed())
            oldContent.dispose();

        PropertySheetDebug.uiVerbose("comfortUi mirror values nativeValues=" + nativeValues //$NON-NLS-1$
                + " nonEmpty=" + nonEmptyValues); //$NON-NLS-1$
        session.entrySignature = entryStructureSignature(entries);
        return true;
    }

    private static boolean isComfortTabSelected(Session session)
    {
        return session != null && session.tabFolder != null && !session.tabFolder.isDisposed()
                && session.comfortTab != null && session.tabFolder.getSelection() == session.comfortTab;
    }

    private static void relayoutContent(Session session)
    {
        if (session == null || session.content == null || session.content.isDisposed())
            return;
        int contentWidth = resolveContentWidth(session);
        session.lastAppliedWidth = contentWidth;
        applyContentSize(session, session.content);
        if (session.tabFolder != null && !session.tabFolder.isDisposed())
            session.tabFolder.layout(true, true);
        if (session.scrolled != null && !session.scrolled.isDisposed())
            session.scrolled.layout(true, true);
        if (session.content != null && !session.content.isDisposed())
            session.content.layout(true, true);
    }

    private static void applyContentSize(Session session, Composite content)
    {
        if (session == null || content == null || content.isDisposed())
            return;
        int contentWidth = resolveContentWidth(session);
        Point size = content.computeSize(contentWidth, SWT.DEFAULT);
        int height = Math.max(size.y, 1);
        content.setSize(contentWidth, height);
        if (session.scrolled != null && !session.scrolled.isDisposed())
            session.scrolled.setMinSize(contentWidth, height);
        relayoutRowWidths(session, contentWidth);
    }

    private static void relayoutRowWidths(Session session, int contentWidth)
    {
        if (session == null || session.rows.isEmpty())
            return;
        int valueWidth = valueColumnWidth(contentWidth);
        for (ComfortRow row : session.rows)
        {
            if (row == null || row.rowComposite == null || row.rowComposite.isDisposed())
                continue;
            Object layoutData = row.rowComposite.getLayoutData();
            if (layoutData instanceof org.eclipse.swt.layout.GridData)
                ((org.eclipse.swt.layout.GridData) layoutData).widthHint = contentWidth;
            if (row.created != null)
                PropertySheetComfortValueControls.applyWidthHint(row.created, valueWidth);
        }
    }

    private static int valueColumnWidth(int contentWidth)
    {
        return Math.max(48, contentWidth - NAME_COLUMN_WIDTH - ROW_HORIZONTAL_INSET - VALUE_NAME_SPACING);
    }

    /** Ширина контента с учётом вертикального скроллбара. */
    private static int resolveContentWidth(Session session)
    {
        int raw = resolveScrolledWidth(session);
        int gutter = verticalScrollGutter(session, raw);
        return Math.max(160, raw - gutter);
    }

    private static int verticalScrollGutter(Session session, int viewportWidth)
    {
        if (session.scrolled == null || session.scrolled.isDisposed())
            return VERTICAL_SCROLL_GUTTER;
        ScrollBar bar = session.scrolled.getVerticalBar();
        if (bar != null && !bar.isDisposed())
        {
            int barWidth = bar.getSize().x;
            if (barWidth > 0)
                return barWidth;
            int max = bar.getMaximum();
            int thumb = bar.getThumb();
            if (max > thumb && thumb > 0)
                return VERTICAL_SCROLL_GUTTER;
        }
        if (session.content != null && !session.content.isDisposed() && viewportWidth > 0)
        {
            int viewHeight = session.scrolled.getClientArea().height;
            if (viewHeight > 0)
            {
                int contentHeight = session.content.computeSize(viewportWidth, SWT.DEFAULT).y;
                if (contentHeight > viewHeight)
                    return VERTICAL_SCROLL_GUTTER;
            }
        }
        if (session.rows.size() > 4)
            return VERTICAL_SCROLL_GUTTER;
        return 0;
    }

    /** Ширина viewport ScrolledComposite (без вычета скроллбара). */
    private static int resolveScrolledWidth(Session session)
    {
        if (session.scrolled != null && !session.scrolled.isDisposed())
        {
            int w = session.scrolled.getClientArea().width;
            if (w > 0)
                return w;
            w = session.scrolled.getBounds().width;
            if (w > 0)
                return Math.max(0, w - verticalScrollGutter(session, w));
        }
        if (session.deck != null && !session.deck.isDisposed())
        {
            int w = session.deck.getBounds().width;
            if (w > 16)
                return w - 16;
        }
        if (session.host != null && !session.host.isDisposed())
        {
            int w = session.host.getBounds().width;
            if (w > 16)
                return w - 16;
        }
        return 320;
    }

    private static String entryStructureSignature(List<PropertySheetViewModelTree.Entry> entries)
    {
        if (entries == null || entries.isEmpty())
            return ""; //$NON-NLS-1$
        List<String> parts = new ArrayList<>();
        for (PropertySheetViewModelTree.Entry entry : entries)
        {
            if (entry == null)
                continue;
            parts.add(entry.kind + "|" + entry.name); //$NON-NLS-1$
        }
        Collections.sort(parts);
        StringBuilder sb = new StringBuilder(parts.size() * 16);
        for (String part : parts)
            sb.append(part).append(';');
        return sb.toString();
    }

    private static void updateRowValues(Session session, List<PropertySheetViewModelTree.Entry> entries)
    {
        if (session.scannerRows.isEmpty())
            session.scannerRows = scannerRowsByName(session.page);
        Map<String, PropertySheetViewModelTree.Entry> byName = new HashMap<>();
        for (PropertySheetViewModelTree.Entry entry : entries)
        {
            if (entry != null && entry.kind == PropertySheetViewModelTree.Kind.PROPERTY
                    && entry.name != null && !entry.name.isEmpty())
                byName.putIfAbsent(entry.name, entry);
        }
        for (ComfortRow row : session.rows)
        {
            if (row == null || row.created == null || row.propertyName == null)
                continue;
            PropertySheetViewModelTree.Entry entry = byName.get(row.propertyName);
            if (entry == null)
                continue;
            PropertySheetPaletteRow scannerRow = session.scannerRows.get(entry.name);
            PropertySheetViewModelTree.ValuePair pair = PropertySheetViewModelTree.resolveValuePair(entry,
                    session.page, scannerRow != null ? scannerRow.lwtView : null);
            Object valueVm = pair.valueVm;
            Object valueView = pair.valueView;
            Control nativeValue = row.nativeValueControl;
            String nativeText = readNativeFieldValue(session, entry.name, nativeValue, pair);
            String display = PropertySheetViewModelTree.resolveEntryDisplay(session.page, entry, nativeText);
            PropertySheetComfortValueControls.applyDisplay(row.created, display, valueVm, valueView, nativeValue);
        }
    }

    private static void enforceVisible(Session session)
    {
        // Вкладку выбирает пользователь вручную — не переключаем автоматически.
    }

    private static void keepNativeVisible(Session session)
    {
        // Вкладку выбирает пользователь вручную — не переключаем автоматически.
    }

    private static void restoreNativePage(Session session)
    {
        // no-op
    }

    private static ComfortRow createSectionRow(Session session, PropertySheetViewModelTree.Entry entry)
    {
        Label label = new Label(session.content, SWT.NONE);
        label.setText(entry.name);
        label.setFont(session.content.getFont());
        GridDataFactory.fillDefaults().grab(true, false).span(2, 1).indent(4, 6).applyTo(label);
        ComfortRow row = new ComfortRow(entry.name, "", label, null, null, null, null); //$NON-NLS-1$
        styleSection(label);
        return row;
    }

    private static ComfortRow createPropertyRow(Session session, PropertySheetViewModelTree.Entry entry,
            PropertySheetPaletteRow nativeRow)
    {
        int contentWidth = resolveContentWidth(session);
        int valueWidth = valueColumnWidth(contentWidth);

        Composite rowComposite = new Composite(session.content, SWT.NONE);
        rowComposite.setLayout(GridLayoutFactory.fillDefaults().numColumns(2).margins(4, 2)
                .spacing(VALUE_NAME_SPACING, 0).create());
        GridDataFactory.fillDefaults().grab(true, false).hint(contentWidth, SWT.DEFAULT).applyTo(rowComposite);

        Label nameLabel = new Label(rowComposite, SWT.NONE);
        nameLabel.setText(entry.name);
        GridDataFactory.swtDefaults().align(SWT.LEFT, SWT.CENTER).hint(NAME_COLUMN_WIDTH, SWT.DEFAULT)
                .applyTo(nameLabel);

        PropertySheetPaletteRow scannerRow = session.scannerRows.get(entry.name);
        PropertySheetViewModelTree.ValuePair pair = PropertySheetViewModelTree.resolveValuePair(entry,
                session.page, scannerRow != null ? scannerRow.lwtView : null);
        Object valueVm = pair.valueVm;
        Object valueView = pair.valueView;
        if (valueVm == null && valueView != null)
        {
            valueVm = Global.getField(valueView, "viewModel"); //$NON-NLS-1$
            if (valueVm == null)
                valueVm = Global.invoke(valueView, "getViewModel"); //$NON-NLS-1$
        }
        if (valueVm == null)
        {
            PropertySheetDebug.valueControl("RESOLVE row MISS " + PropertySheetDebug.quote(entry.name) //$NON-NLS-1$
                    + " path=" + PropertySheetDebug.quote(pair.path) //$NON-NLS-1$
                    + " scanner=" + (scannerRow != null ? "yes" : "no") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                    + " fieldRow=" + PropertySheetDebug.typeName(entry.viewModel)); //$NON-NLS-1$
        }
        PropertySheetPaletteRow rowSource = nativeRow != null ? nativeRow : scannerRow;
        Control nativeValue = nativeValueControl(rowSource, valueView);
        if (nativeValue == null && scannerRow != null && scannerRow != nativeRow)
            nativeValue = nativeValueControl(scannerRow, valueView);
        if (nativeValue == null && valueVm != null)
        {
            Object scene = Global.invoke(session.page, "getScene"); //$NON-NLS-1$
            nativeValue = PropertySheetControlInterop.resolveNameControl(scene, valueVm,
                    valueView, entry.name);
        }
        String nativeText = readNativeFieldValue(session, entry.name, nativeValue, pair);
        if (nativeText.isEmpty() && rowSource != null)
            nativeText = readNativeFieldValue(session, entry.name,
                    nativeValueControl(rowSource, null), pair);
        if (nativeText.isEmpty() && scannerRow != null && scannerRow.lwtView != null)
        {
            PropertySheetViewModelTree.ValuePair fromLabel =
                    PropertySheetViewModelTree.resolveValuePair(entry, session.page, scannerRow.lwtView);
            nativeText = readNativeFieldValue(session, entry.name, null, fromLabel);
        }
        if (valueView == null && valueVm != null)
            valueView = PropertySheetViewModelTree.resolveValueView(session.page, valueVm);
        PropertySheetViewModelTree.Entry resolvedEntry = entry;
        if (valueVm != entry.valueViewModel || (valueView != null && valueView != entry.valueView))
        {
            resolvedEntry = new PropertySheetViewModelTree.Entry(entry.kind, entry.name, entry.value,
                    entry.viewModel, valueVm, valueView);
        }
        String display = PropertySheetViewModelTree.resolveEntryDisplay(session.page, resolvedEntry, nativeText);
        PropertySheetComfortValueControls.Created created = PropertySheetComfortValueControls.create(
                rowComposite, resolvedEntry, nativeValue, display, pair.path, valueWidth);

        ComfortRow row = new ComfortRow(entry.name, created.displayValue, nameLabel, created,
                rowComposite, nativeValue, valueVm);
        wireRow(session, row);
        session.paletteRows.add(new PropertySheetPaletteRow(nameLabel, rowComposite,
                new Control[] { nameLabel, created.control }, entry.name));
        return row;
    }

    private static void wireRow(Session session, ComfortRow row)
    {
        MouseAdapter click = new MouseAdapter()
        {
            @Override
            public void mouseDown(MouseEvent e)
            {
                if (e.button != 1)
                    return;
                selectRow(session, row);
            }
        };
        row.rowComposite.addMouseListener(click);
        row.nameLabel.addMouseListener(click);
        if (row.valueControl() != null)
            row.valueControl().addMouseListener(click);
        PropertySheetComfortValueControls.wireChange(row.created, () -> {
            if (row.updatingFromNative)
                return;
            PropertySheetComfortValueControls.applyToNative(session, row.created, row.nativeValueControl,
                    row.valueViewModel, row.propertyName);
        });

        Menu menu = new Menu(row.rowComposite.getShell(), SWT.POP_UP);
        menu.addMenuListener(new MenuAdapter()
        {
            @Override
            public void menuShown(MenuEvent e)
            {
                for (MenuItem item : menu.getItems())
                    item.dispose();
                MenuItem copyItem = new MenuItem(menu, SWT.PUSH);
                copyItem.setText("Копировать имя\tCtrl+C"); //$NON-NLS-1$
                if (row.propertyName.isEmpty())
                    copyItem.setEnabled(false);
                else
                {
                    copyItem.addListener(SWT.Selection, ev -> {
                        PropertySheetUiContext.copyToClipboard(row.nameLabel, row.propertyName);
                        ToastNotification.show("Скопировано", row.propertyName, 2_500); //$NON-NLS-1$
                    });
                }
            }
        });
        row.rowComposite.setMenu(menu);
        row.nameLabel.setMenu(menu);
        if (row.valueControl() != null)
            row.valueControl().setMenu(menu);

        ensureHighlightHook(row.nameLabel);
        row.rowComposite.addPaintListener(new PaintListener()
        {
            @Override
            public void paintControl(PaintEvent e)
            {
                if (session.selected != row)
                    return;
                drawSelectionBand(e.gc, row.rowComposite);
            }
        });
    }

    private static void selectRow(Session session, ComfortRow row)
    {
        if (session.selected != null && session.selected.rowComposite != null
                && !session.selected.rowComposite.isDisposed())
            session.selected.rowComposite.redraw();
        session.selected = row;
        session.selectedName = row != null ? row.propertyName : null;
        if (row != null && row.rowComposite != null && !row.rowComposite.isDisposed())
            row.rowComposite.redraw();
        PropertySheetDebug.feature("comfortSelect " + PropertySheetDebug.quote(row.propertyName)); //$NON-NLS-1$
    }

    private static void restoreSelection(Session session)
    {
        if (session.selectedName == null)
            return;
        for (ComfortRow row : session.rows)
        {
            if (session.selectedName.equals(row.propertyName))
            {
                selectRow(session, row);
                return;
            }
        }
    }

    private static void applyHighlights(Session session)
    {
        SmartMatcher matcher = session.matcher != null ? session.matcher : new SmartMatcher(""); //$NON-NLS-1$
        for (ComfortRow row : session.rows)
        {
            if (row.nameLabel == null || row.nameLabel.isDisposed())
                continue;
            if (matcher.isEmpty || !matcher.matches(row.propertyName))
            {
                row.nameLabel.setData(HIGHLIGHT_KEY + ".matcher", null); //$NON-NLS-1$
                row.nameLabel.setData(HIGHLIGHT_KEY + ".text", null); //$NON-NLS-1$
            }
            else
            {
                row.nameLabel.setData(HIGHLIGHT_KEY + ".matcher", matcher); //$NON-NLS-1$
                row.nameLabel.setData(HIGHLIGHT_KEY + ".text", row.propertyName); //$NON-NLS-1$
            }
            row.nameLabel.redraw();
        }
    }

    private static void ensureHighlightHook(Label label)
    {
        if (Boolean.TRUE.equals(label.getData(HIGHLIGHT_KEY)))
            return;
        label.setData(HIGHLIGHT_KEY, Boolean.TRUE);
        label.addPaintListener(new PaintListener()
        {
            @Override
            public void paintControl(PaintEvent e)
            {
                SmartMatcher matcher = (SmartMatcher) label.getData(HIGHLIGHT_KEY + ".matcher"); //$NON-NLS-1$
                String text = (String) label.getData(HIGHLIGHT_KEY + ".text"); //$NON-NLS-1$
                if (matcher == null || text == null || text.isEmpty())
                    return;
                SmartMatchHighlight.paintTextMatchOverlay(e.gc, label, text, matcher);
            }
        });
    }

    private static void drawSelectionBand(GC gc, Composite rowComposite)
    {
        if (gc == null || rowComposite == null || rowComposite.isDisposed())
            return;
        int w = rowComposite.getSize().x;
        int h = rowComposite.getSize().y;
        if (w <= 0 || h <= 0)
            return;
        Color sel = rowComposite.getDisplay().getSystemColor(SWT.COLOR_LIST_SELECTION);
        int alpha = gc.getAlpha();
        gc.setAlpha(80);
        gc.setBackground(sel);
        gc.fillRectangle(0, 0, w, h);
        gc.setAlpha(alpha);
    }

    private static void styleSection(Label label)
    {
        if (label == null || label.isDisposed())
            return;
        org.eclipse.swt.graphics.FontData[] data = label.getFont().getFontData();
        for (org.eclipse.swt.graphics.FontData fd : data)
            fd.setStyle(SWT.BOLD);
        org.eclipse.swt.graphics.Font bold =
                new org.eclipse.swt.graphics.Font(label.getDisplay(), data);
        label.setFont(bold);
        label.addDisposeListener(e -> {
            if (!bold.isDisposed())
                bold.dispose();
        });
    }

    private static void invalidateLookupCaches(Session session)
    {
        if (session == null)
            return;
        session.nativeRows.clear();
        session.nativeRowsResolved = false;
        session.scannerRows.clear();
        PropertySheetDebug.uiVerbose("comfortUi INVALIDATE lookup caches page=" //$NON-NLS-1$
                + PropertySheetDebug.safe(session.page));
    }

    private static void clearContent(Composite content)
    {
        if (content == null || content.isDisposed())
            return;
        for (Control child : content.getChildren())
        {
            if (child != null && !child.isDisposed())
                child.dispose();
        }
    }

    private static Map<String, PropertySheetPaletteRow> scannerRowsByName(Object page)
    {
        Map<String, PropertySheetPaletteRow> out = new HashMap<>();
        if (page == null)
            return out;
        Object scene = Global.invoke(page, "getScene"); //$NON-NLS-1$
        if (scene == null)
            return out;
        for (PropertySheetPaletteRow row : PropertySheetPaletteScanner.scan(scene, page))
        {
            if (row != null && row.propertyName != null && !row.propertyName.isEmpty())
                out.putIfAbsent(row.propertyName, row);
        }
        PropertySheetDebug.uiVerbose("comfortUi scanner rows=" + out.size()); //$NON-NLS-1$
        return out;
    }

    private static Map<String, PropertySheetPaletteRow> nativeRowsByName(Session session,
            List<PropertySheetViewModelTree.Entry> entries)
    {
        if (!session.nativeRows.isEmpty() || session.nativeRowsResolved)
            return session.nativeRows;
        session.nativeRowsResolved = true;
        Object scene = session.page != null ? Global.invoke(session.page, "getScene") : null; //$NON-NLS-1$
        if (scene == null || entries == null)
            return session.nativeRows;
        for (PropertySheetViewModelTree.Entry entry : entries)
        {
            if (entry == null || entry.kind != PropertySheetViewModelTree.Kind.PROPERTY
                    || entry.name.isEmpty() || session.nativeRows.containsKey(entry.name))
                continue;
            Object valueView = PropertySheetViewModelTree.resolveEntryValueView(entry, session.page);
            Object valueVm = PropertySheetViewModelTree.resolveEntryValueViewModel(entry, session.page);
            Control nameControl = PropertySheetControlInterop.resolveNameControl(scene, entry.viewModel,
                    null, entry.name);
            Control valueControl = valueVm != null
                    ? PropertySheetControlInterop.resolveNameControl(scene, valueVm, valueView, entry.name)
                    : null;
            Control anchor = nameControl != null ? nameControl : valueControl;
            if (anchor == null && session.nativeContent != null && !session.nativeContent.isDisposed())
            {
                Composite root = nativePaletteRoot(session.nativeContent);
                anchor = findControlByExactText(root, entry.name);
            }
            if (anchor == null)
                continue;
            Composite row = PropertySheetUiContext.fieldRowOf(anchor);
            session.nativeRows.putIfAbsent(entry.name, new PropertySheetPaletteRow(anchor, row,
                    PropertySheetUiContext.rowControls(row, anchor), entry.name, valueView));
        }
        PropertySheetDebug.uiVerbose("comfortUi native rows=" + session.nativeRows.size()); //$NON-NLS-1$
        return session.nativeRows;
    }

    private static String readNativeFieldValue(Session session, String propertyName, Control nativeValue,
            PropertySheetViewModelTree.ValuePair pair)
    {
        if (pair != null)
        {
            if (pair.valueView != null)
            {
                String fromView = PropertySheetControlInterop.displayTextFromView(pair.valueView, pair.valueVm);
                if (!fromView.isEmpty() && !PropertySheetComfortValueControls.isOpenPlaceholder(fromView))
                    return fromView;
            }
            if (pair.valueVm != null)
            {
                String fromVm = PropertySheetAefValues.readValue(pair.valueVm);
                if (!fromVm.isEmpty() && !PropertySheetComfortValueControls.isOpenPlaceholder(fromVm))
                    return fromVm;
            }
        }
        if (nativeValue != null && !nativeValue.isDisposed())
        {
            String text = readBestNativeValueText(nativeValue);
            if (!text.isEmpty() && !PropertySheetComfortValueControls.isOpenPlaceholder(text))
                return text;
        }
        if (session == null || session.nativeContent == null || session.nativeContent.isDisposed()
                || propertyName == null || propertyName.isEmpty())
            return ""; //$NON-NLS-1$
        Composite root = nativePaletteRoot(session.nativeContent);
        if (root == null)
            return ""; //$NON-NLS-1$
        Control nameControl = findControlByExactText(root, propertyName);
        if (nameControl == null)
            return ""; //$NON-NLS-1$
        Composite row = PropertySheetUiContext.fieldRowOf(nameControl);
        if (row == null)
            return ""; //$NON-NLS-1$
        return readBestNativeRowValue(row, nameControl);
    }

    private static String readBestNativeRowValue(Composite row, Control nameControl)
    {
        if (row == null || row.isDisposed())
            return ""; //$NON-NLS-1$
        String placeholder = ""; //$NON-NLS-1$
        for (Control child : row.getChildren())
        {
            if (child == null || child.isDisposed() || child == nameControl)
                continue;
            if (PropertySheetUiContext.isFilterAreaControl(child))
                continue;
            String text = readBestNativeValueText(child);
            if (text.isEmpty())
                continue;
            if (PropertySheetComfortValueControls.isOpenPlaceholder(text))
            {
                if (placeholder.isEmpty())
                    placeholder = text;
                continue;
            }
            return text;
        }
        return PropertySheetComfortValueControls.isOpenPlaceholder(placeholder) ? "" : placeholder; //$NON-NLS-1$
    }

    private static String readBestNativeValueText(Control control)
    {
        if (control == null || control.isDisposed())
            return ""; //$NON-NLS-1$
        if (control instanceof Text)
            return ((Text) control).getText();
        if (control instanceof Composite)
        {
            for (Control child : ((Composite) control).getChildren())
            {
                if (child instanceof Text && !child.isDisposed())
                {
                    String nested = ((Text) child).getText();
                    if (!nested.isEmpty())
                        return nested;
                }
            }
        }
        return PropertySheetControlInterop.controlText(control);
    }

    private static Composite nativePaletteRoot(Control nativeContent)
    {
        if (nativeContent == null || nativeContent.isDisposed())
            return null;
        if (nativeContent instanceof org.eclipse.swt.custom.ScrolledComposite)
        {
            Control content = ((org.eclipse.swt.custom.ScrolledComposite) nativeContent).getContent();
            return content instanceof Composite ? (Composite) content : null;
        }
        return nativeContent instanceof Composite ? (Composite) nativeContent : null;
    }

    private static Control findControlByExactText(Composite composite, String text)
    {
        if (composite == null || composite.isDisposed() || text == null || text.isEmpty())
            return null;
        for (Control child : composite.getChildren())
        {
            if (child == null || child.isDisposed())
                continue;
            if (text.equals(PropertySheetControlInterop.controlText(child)))
                return child;
            if (child instanceof Composite)
            {
                Control found = findControlByExactText((Composite) child, text);
                if (found != null)
                    return found;
            }
        }
        return null;
    }

    private static Control nativeValueControl(PropertySheetPaletteRow nativeRow, Object valueView)
    {
        PropertySheetComfortValueControls.Kind viewKind =
                PropertySheetComfortValueControls.kindFromViewPublic(valueView);
        if (nativeRow == null || nativeRow.rowControls == null)
            return null;
        for (Control control : nativeRow.rowControls)
        {
            Control value = findEditableValueControl(control, viewKind);
            if (value != null && value != nativeRow.nameControl)
                return value;
        }
        if (nativeRow.rowComposite != null && !nativeRow.rowComposite.isDisposed())
            return findEditableValueControl(nativeRow.rowComposite, viewKind);
        return null;
    }

    private static Control findEditableValueControl(Control control,
            PropertySheetComfortValueControls.Kind viewKind)
    {
        if (control == null || control.isDisposed() || PropertySheetUiContext.isFilterAreaControl(control))
            return null;
        boolean preferLink = viewKind == PropertySheetComfortValueControls.Kind.HYPERLINK;
        if (!preferLink && control instanceof Text)
            return control;
        if (preferLink && control instanceof org.eclipse.swt.widgets.Link)
            return control;
        if (isWritableValueControl(control) && !(control instanceof org.eclipse.swt.widgets.Link))
            return control;
        if (control instanceof Composite)
        {
            Control textFallback = null;
            Control linkFallback = null;
            for (Control child : ((Composite) control).getChildren())
            {
                Control found = findEditableValueControl(child, viewKind);
                if (found == null)
                    continue;
                if (found instanceof Text)
                {
                    if (!preferLink)
                        return found;
                    if (textFallback == null)
                        textFallback = found;
                    continue;
                }
                if (found instanceof org.eclipse.swt.widgets.Link)
                {
                    if (preferLink)
                        return found;
                    if (linkFallback == null)
                        linkFallback = found;
                    continue;
                }
                return found;
            }
            return preferLink ? (linkFallback != null ? linkFallback : textFallback) : textFallback;
        }
        if (control instanceof org.eclipse.swt.widgets.Link)
            return preferLink ? control : null;
        return isWritableValueControl(control) ? control : null;
    }

    private static boolean isWritableValueControl(Control control)
    {
        if (control instanceof Text || control instanceof org.eclipse.swt.widgets.Combo
                || control instanceof CCombo)
            return true;
        if (control instanceof org.eclipse.swt.widgets.Button
                && (((org.eclipse.swt.widgets.Button) control).getStyle() & SWT.CHECK) != 0)
            return true;
        if (control instanceof org.eclipse.swt.widgets.Spinner || control instanceof org.eclipse.swt.widgets.Link)
            return true;
        String cn = control.getClass().getName();
        return cn.contains("Text") || cn.contains("Combo") || cn.contains("Spinner") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                || cn.contains("Check") || cn.contains("Boolean") || cn.contains("Hyperlink") //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                || cn.contains("Link"); //$NON-NLS-1$
    }

    private static final class Session implements SessionAccessor
    {
        final Object page;
        final Composite host;
        final Composite deck;
        final Control nativeContent;
        final org.eclipse.swt.custom.ScrolledComposite scrolled;
        final CTabItem comfortTab;
        final CTabFolder tabFolder;
        Composite content;
        final List<ComfortRow> rows = new ArrayList<>();
        final List<PropertySheetPaletteRow> paletteRows = new ArrayList<>();
        final Map<String, PropertySheetPaletteRow> nativeRows = new HashMap<>();
        Map<String, PropertySheetPaletteRow> scannerRows = new HashMap<>();
        boolean nativeRowsResolved;
        SmartMatcher matcher = new SmartMatcher(""); //$NON-NLS-1$
        ComfortRow selected;
        String selectedName;
        boolean mirrorShown;
        String entrySignature = ""; //$NON-NLS-1$
        String sourceKey = ""; //$NON-NLS-1$
        int lastAppliedWidth = -1;

        Session(Object page, Composite host, Composite deck, Control nativeContent,
                org.eclipse.swt.custom.ScrolledComposite scrolled, Composite content,
                CTabItem comfortTab, CTabFolder tabFolder)
        {
            this.page = page;
            this.host = host;
            this.deck = deck;
            this.nativeContent = nativeContent;
            this.scrolled = scrolled;
            this.content = content;
            this.comfortTab = comfortTab;
            this.tabFolder = tabFolder;
        }

        @Override
        public Object page()
        {
            return page;
        }

        @Override
        public Composite deck()
        {
            return deck;
        }

        @Override
        public Control resolveNativeValueControl(String propertyName, Object valueVm)
        {
            PropertySheetPaletteRow row = nativeRows.get(propertyName);
            Object valueView = row != null ? row.lwtView : null;
            if (valueView == null && valueVm != null && page != null)
                valueView = PropertySheetViewModelTree.resolveValueView(page, valueVm);
            Control control = nativeValueControl(row, valueView);
            if (control != null)
                return control;
            if (valueVm == null)
                return null;
            Object scene = page != null ? Global.invoke(page, "getScene") : null; //$NON-NLS-1$
            return PropertySheetControlInterop.resolveNameControl(scene, valueVm, valueView, propertyName);
        }
    }

    private static final class ComfortRow
    {
        final String propertyName;
        final String valueText;
        final Label nameLabel;
        final PropertySheetComfortValueControls.Created created;
        final Composite rowComposite;
        final Control nativeValueControl;
        final Object valueViewModel;
        boolean updatingFromNative;

        ComfortRow(String propertyName, String valueText, Label nameLabel,
                PropertySheetComfortValueControls.Created created, Composite rowComposite,
                Control nativeValueControl, Object valueViewModel)
        {
            this.propertyName = propertyName;
            this.valueText = valueText;
            this.nameLabel = nameLabel;
            this.created = created;
            this.rowComposite = rowComposite;
            this.nativeValueControl = nativeValueControl;
            this.valueViewModel = valueViewModel;
        }

        Control valueControl()
        {
            return created != null ? created.control : null;
        }
    }
}
