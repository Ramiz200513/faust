// Снимок дерева элементов окна через Windows UI Automation -> JSON в stdout.
//
// Использование:
//   UiaDump.exe --hwnd <число>        снять дерево окна с этим хэндлом
//   UiaDump.exe --foreground          снять дерево окна, которое сейчас в фокусе
// Необязательно:
//   --max-depth <n>      насколько глубоко спускаться по дереву (по умолчанию 12)
//   --max-elements <n>   сколько элементов максимум вернуть (по умолчанию 300)
//
// Почему именно так, а не иначе - подробности в UiaDump.csproj.

using System.Diagnostics;
using System.Globalization;
using System.Runtime.InteropServices;
using System.Text.Json;
using System.Windows;
using System.Windows.Automation;

internal static class Program
{
    [DllImport("user32.dll")]
    private static extern IntPtr GetForegroundWindow();

    private static int Main(string[] args)
    {
        IntPtr hwnd = IntPtr.Zero;
        int maxDepth = 12;
        int maxElements = 300;

        for (int i = 0; i < args.Length; i++)
        {
            switch (args[i])
            {
                case "--hwnd" when i + 1 < args.Length:
                    hwnd = new IntPtr(long.Parse(args[++i], CultureInfo.InvariantCulture));
                    break;
                case "--foreground":
                    hwnd = GetForegroundWindow();
                    break;
                case "--max-depth" when i + 1 < args.Length:
                    maxDepth = int.Parse(args[++i], CultureInfo.InvariantCulture);
                    break;
                case "--max-elements" when i + 1 < args.Length:
                    maxElements = int.Parse(args[++i], CultureInfo.InvariantCulture);
                    break;
            }
        }

        if (hwnd == IntPtr.Zero)
        {
            WriteJson(new { error = "не указано окно: нужен --hwnd <число> или --foreground" });
            return 1;
        }

        var sw = Stopwatch.StartNew();

        // Запрос с кэшем - главная оптимизация снимка.
        //
        // Без него каждое обращение к свойству элемента (имя, тип, прямоугольник,
        // поддерживаемые действия) - это отдельный вызов в ЧУЖОЙ процесс, то есть
        // 8-10 переключений контекста на элемент. На окне проводника набегало
        // под секунду. С кэшем Windows отдаёт все перечисленные свойства сразу
        // для целой пачки элементов, а дальше мы читаем их локально (.Cached).
        var cache = BuildCacheRequest();

        AutomationElement? root;
        using var cacheScope = cache.Activate();
        try
        {
            root = AutomationElement.FromHandle(hwnd).GetUpdatedCache(cache);
        }
        catch (Exception e)
        {
            WriteJson(new { error = "не удалось получить элемент окна: " + e.Message });
            return 1;
        }

        if (root == null)
        {
            WriteJson(new { error = "окно не найдено (возможно, уже закрыто)" });
            return 1;
        }

        Rect windowRect;
        string windowTitle;
        try
        {
            windowRect = root.Cached.BoundingRectangle;
            windowTitle = root.Cached.Name ?? "";
        }
        catch (Exception e)
        {
            WriteJson(new { error = "окно не отвечает: " + e.Message });
            return 1;
        }

        var walker = new Walker(windowRect, maxDepth, maxElements);
        walker.Walk(root, 0);
        sw.Stop();

        WriteJson(new
        {
            windowTitle,
            windowRect = Fmt(windowRect),
            elementCount = walker.Elements.Count,
            truncated = walker.Truncated,
            elapsedMs = sw.ElapsedMilliseconds,
            elements = walker.Elements
        });
        return 0;
    }

    /// <summary>
    /// Какие свойства забирать одним запросом. Всё, что читается в TryAdd,
    /// должно быть перечислено здесь: обращение к незакэшированному свойству
    /// через .Cached бросает исключение.
    /// </summary>
    private static CacheRequest BuildCacheRequest()
    {
        var cache = new CacheRequest();
        cache.Add(AutomationElement.NameProperty);
        cache.Add(AutomationElement.ControlTypeProperty);
        cache.Add(AutomationElement.BoundingRectangleProperty);
        cache.Add(AutomationElement.AutomationIdProperty);
        cache.Add(AutomationElement.ClassNameProperty);
        cache.Add(AutomationElement.IsPasswordProperty);
        cache.Add(AutomationElement.IsEnabledProperty);
        cache.Add(AutomationElement.IsOffscreenProperty);
        cache.Add(AutomationElement.IsKeyboardFocusableProperty);
        cache.Add(AutomationElement.HasKeyboardFocusProperty);
        cache.Add(InvokePattern.Pattern);
        cache.Add(ValuePattern.Pattern);
        cache.Add(TogglePattern.Pattern);
        cache.Add(SelectionItemPattern.Pattern);
        cache.Add(ExpandCollapsePattern.Pattern);
        // Значение поля ввода: агенту важно видеть, что в поле уже что-то набрано,
        // иначе он допишет свой текст к чужому.
        cache.Add(ValuePattern.ValueProperty);

        // Полное дерево, а не ControlView (который стоит здесь по умолчанию).
        // Замер на одном и том же окне Блокнота: ControlView - 57 элементов и
        // 149 мс, полное дерево - 75 элементов и 160 мс. За 7% времени мы видим
        // на 18 элементов больше, а лишнее дешевле отсеять уже на стороне Java,
        // где правила отбора видно и можно менять без пересборки помощника.
        cache.TreeFilter = Automation.RawViewCondition;
        return cache;
    }

    private static void WriteJson(object value)
    {
        // Пишем в стандартный вывод сырыми байтами UTF-8, а не через Console.WriteLine.
        // Console перекодирует текст в кодовую страницу консоли Windows (866/1251),
        // и кириллица приходит в Java побитой: "Изменить" превращалось в "?зменить".
        // WriteIndented не включаем: JSON читает программа, а не человек.
        byte[] utf8 = JsonSerializer.SerializeToUtf8Bytes(value, new JsonSerializerOptions
        {
            Encoder = System.Text.Encodings.Web.JavaScriptEncoder.UnsafeRelaxedJsonEscaping
        });
        using var stdout = Console.OpenStandardOutput();
        stdout.Write(utf8, 0, utf8.Length);
        stdout.Flush();
    }

    private static string Fmt(Rect r) =>
        $"{(int)r.X},{(int)r.Y},{(int)r.Width},{(int)r.Height}";

    /// <summary>
    /// Обход дерева окна вглубь с двумя важными ограничениями.
    ///
    /// 1. Не спускаемся в элементы, которые целиком вне видимой части окна.
    ///    Это не оптимизация ради оптимизации: списки в современных программах
    ///    "виртуальные" - UI Automation отдаёт в них ВСЕ строки, включая те,
    ///    что прокручены далеко за пределы экрана. В Telegram так выгружалась
    ///    вся история переписки (тысячи элементов, секунды времени). Пользователь
    ///    этих строк не видит, агенту они не нужны, а в журнал задачи попадать
    ///    не должны.
    ///
    /// 2. Ограничение по глубине и количеству - защита от совсем уж больших
    ///    деревьев, чтобы снимок не занимал секунды.
    /// </summary>
    private sealed class Walker
    {
        private readonly Rect _windowRect;
        private readonly int _maxDepth;
        private readonly int _maxElements;

        public List<object> Elements { get; } = new();
        public bool Truncated { get; private set; }

        public Walker(Rect windowRect, int maxDepth, int maxElements)
        {
            _windowRect = windowRect;
            _maxDepth = maxDepth;
            _maxElements = maxElements;
        }

        public void Walk(AutomationElement el, int depth)
        {
            if (Elements.Count >= _maxElements)
            {
                Truncated = true;
                return;
            }
            if (depth > _maxDepth) return;

            Rect rect;
            try
            {
                rect = el.Cached.BoundingRectangle;
            }
            catch
            {
                return; // элемент исчез между обходом и чтением свойств - бывает в живом интерфейсе
            }

            bool empty = rect.Width <= 0 || rect.Height <= 0 || double.IsInfinity(rect.Width);
            // Пересекается ли элемент с видимой частью окна. Пустой rect (у элементов
            // без собственной геометрии - контейнеров, например) не повод отбрасывать
            // поддерево: дети такого элемента вполне могут быть на экране.
            bool visible = !empty && rect.IntersectsWith(_windowRect);

            if (!empty && !visible) return; // сам за экраном и геометрия известна -> и дети там же

            // DataItem - это служебные подполя строки списка ("Отправитель", "Время",
            // "Размер файла"). Кликать по ним бессмысленно: действие выполняется над
            // самой строкой, а не над её полем. В Telegram они дают 60% всего дерева
            // и заметную часть времени обхода, а ещё именно в них лежит текст
            // переписки - агенту он для клика не нужен.
            if (IsDataItem(el)) return;

            if (visible) TryAdd(el, rect, depth);

            if (Elements.Count >= _maxElements)
            {
                Truncated = true;
                return;
            }

            try
            {
                var children = el.FindAll(TreeScope.Children, System.Windows.Automation.Condition.TrueCondition);
                foreach (AutomationElement child in children)
                {
                    Walk(child, depth + 1);
                    if (Elements.Count >= _maxElements)
                    {
                        Truncated = true;
                        return;
                    }
                }
            }
            catch
            {
                // окно может закрыться прямо во время обхода - это не повод падать,
                // отдадим то, что уже собрали
            }
        }

        private static bool IsDataItem(AutomationElement el)
        {
            try
            {
                return el.Cached.ControlType == ControlType.DataItem;
            }
            catch
            {
                return false;
            }
        }

        /// <summary>
        /// Что сейчас набрано в поле. Нужно, чтобы агент не дописывал свой текст
        /// к чужому: увидев непустое значение, модель сначала очистит поле.
        /// У поля пароля значение не берём никогда.
        /// </summary>
        private static string CachedValue(AutomationElement el)
        {
            try
            {
                if (el.Cached.IsPassword) return "";
                if (!el.TryGetCachedPattern(ValuePattern.Pattern, out var p)) return "";
                return ((ValuePattern)p).Cached.Value ?? "";
            }
            catch
            {
                return "";
            }
        }

        private void TryAdd(AutomationElement el, Rect rect, int depth)
        {
            try
            {
                var c = el.Cached;
                var patterns = new List<string>();
                if (el.TryGetCachedPattern(InvokePattern.Pattern, out _)) patterns.Add("Invoke");
                if (el.TryGetCachedPattern(ValuePattern.Pattern, out _)) patterns.Add("Value");
                if (el.TryGetCachedPattern(TogglePattern.Pattern, out _)) patterns.Add("Toggle");
                if (el.TryGetCachedPattern(SelectionItemPattern.Pattern, out _)) patterns.Add("SelectionItem");
                if (el.TryGetCachedPattern(ExpandCollapsePattern.Pattern, out _)) patterns.Add("ExpandCollapse");

                Elements.Add(new
                {
                    depth,
                    controlType = c.ControlType?.LocalizedControlType ?? "",
                    name = c.Name ?? "",
                    automationId = c.AutomationId ?? "",
                    className = c.ClassName ?? "",
                    isPassword = c.IsPassword,
                    isEnabled = c.IsEnabled,
                    isOffscreen = c.IsOffscreen,
                    isKeyboardFocusable = c.IsKeyboardFocusable,
                    hasFocus = c.HasKeyboardFocus,
                    value = CachedValue(el),
                    rect = Fmt(rect),
                    patterns = string.Join(",", patterns)
                });
            }
            catch
            {
                // элемент пропал - пропускаем, обход продолжается
            }
        }
    }
}
