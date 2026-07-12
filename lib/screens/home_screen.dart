import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:safety_screen/res/data_color.dart';
import 'dart:ui' show ImageFilter;
import 'package:safety_screen/res/resourse.dart';
import 'package:safety_screen/themes/gold_ornament.dart';
import 'package:safety_screen/themes/islamic_background.dart';
import 'package:safety_screen/widgets/islamic_card.dart';
import 'package:safety_screen/widgets/mode_card.dart';
import 'package:connectivity_plus/connectivity_plus.dart';
import 'package:flutter_styled_toast/flutter_styled_toast.dart';
import 'dart:async';
import 'dart:io' show InternetAddress, SocketException;

class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key});

  @override
  State<HomeScreen> createState() => _SafeScreenHomeState();
}

class _SafeScreenHomeState extends State<HomeScreen>
    with TickerProviderStateMixin {
  static const _channel = MethodChannel('com.ghadhoo_buler/screen_monitor');

  bool _isActive = false;
  int _androidVersion = 0;
  Color _selectedColor = const Color(0xFF0A0A0A);
  double _blurRadius = 15.0;

  // ✅ متغير واحد بدل اثنين: true = محلي، false = احترافي (سحابي)
  // القيمة الافتراضية محلي دائماً عند أول فتح للتطبيق (لا يُحمَّل من تفضيلات محفوظة)
  bool _useLocalAi = true;

  // ✅ جديد: حالة الاتصال بالإنترنت — تتحكم بتعطيل بطاقة "احترافي" فوراً
  // (مو بس وقت الضغط) عبر مراقبة مستمرة لتغيّر الاتصال
  bool _hasInternet = true;
  StreamSubscription<List<ConnectivityResult>>? _connectivitySub;

  // ✅ جديد: فحص دوري للاتصال الفعلي بالإنترنت (وليس فقط توفر واي فاي/بيانات
  // كواجهة). يغطي حالة "متصل بالراوتر لكن بدون إنترنت خلفه" التي لا يكتشفها
  // connectivity_plus وحده لأنه يقرأ نوع الواجهة فقط دون اختبار وصول فعلي
  Timer? _internetCheckTimer;
  bool _isCheckingInternet = false;

  late AnimationController _pulseController;
  late AnimationController _rotateController;
  late Animation<double> _pulseAnim;

  static List<ColorOption> _colorOptions = [
    ColorOption('أسود', Color(0xFF0A0A0A)),
    ColorOption('أخضر ', Color(0xFF1A3C2A)),
    ColorOption('أزرق ', Color(0xFF0D1B2A)),
    ColorOption('بنفسجي', Color(0xFF1E1030)),
    ColorOption('رمادي', Color(0xFF1A1A1A)),
    ColorOption('بني ', Color(0xFF2C1500)),
  ];

  @override
  void initState() {
    super.initState();
    _pulseController = AnimationController(
      vsync: this,
      duration: const Duration(seconds: 2),
    )..repeat(reverse: true);
    _rotateController = AnimationController(
      vsync: this,
      duration: const Duration(seconds: 30),
    )..repeat();
    _pulseAnim = Tween<double>(begin: 0.95, end: 1.05).animate(
      CurvedAnimation(parent: _pulseController, curve: Curves.easeInOut),
    );
    _loadAndroidVersion();
    _checkInitialStatus();
    _initConnectivityMonitoring();
  }

  // ✅ يفحص الاتصال فوراً عند فتح الشاشة، ثم يراقب أي تغيير لاحق (تفعيل/
  // إيقاف الواي فاي أو بيانات الجوال، أو انقطاع الإنترنت الفعلي خلف اتصال
  // ظاهرياً "شغال") ويحدّث _hasInternet تلقائياً — هذا يخلي بطاقة "احترافي"
  // تتعطّل بصرياً فوراً لحظة انقطاع الإنترنت، بدون ما ينتظر المستخدم يضغط
  // عليها الأول
  Future<void> _initConnectivityMonitoring() async {
    await _refreshInternetStatus();

    // أي تغيّر بنوع الواجهة (واي فاي/بيانات/لا شيء) يستدعي فحصاً فعلياً فوراً
    _connectivitySub = Connectivity().onConnectivityChanged.listen((_) {
      _refreshInternetStatus();
    });

    // ✅ جديد: فحص دوري كل 10 ثوانٍ — يغطي حالة "واي فاي/بيانات شغالة لكن
    // بدون إنترنت فعلي خلفها" (مثلاً راوتر متصل بلا خط)، وهي حالة لا يُصدر
    // فيها connectivity_plus أي حدث تغيير لأن الواجهة نفسها لم تتغيّر
    _internetCheckTimer = Timer.periodic(const Duration(seconds: 10), (_) {
      _refreshInternetStatus();
    });
  }

  // ✅ جديد: يجمع بين فحص توفر واجهة الاتصال (واي فاي/بيانات) وفحص وصول
  // فعلي حقيقي للإنترنت (DNS lookup)، ويحدّث _hasInternet بناءً على النتيجة
  // الحقيقية فقط — لا يكفي أن تكون الواجهة "شغالة" لاعتبار الإنترنت متوفراً
  Future<void> _refreshInternetStatus() async {
    if (_isCheckingInternet) return;
    _isCheckingInternet = true;
    try {
      final transportResults = await Connectivity().checkConnectivity();
      final hasTransport = !transportResults.contains(ConnectivityResult.none);

      final actuallyConnected =
          hasTransport ? await _hasActualInternetAccess() : false;

      if (mounted && actuallyConnected != _hasInternet) {
        setState(() => _hasInternet = actuallyConnected);
      }
    } finally {
      _isCheckingInternet = false;
    }
  }

  // ✅ جديد: فحص وصول فعلي للإنترنت عبر محاولة DNS lookup لأكثر من نطاق
  // معروف وموثوق (بدل الاعتماد على نطاق واحد فقط قد لا يكون مدعوماً في كل
  // الشبكات/الدول أو يتأخر أحياناً)، بمهلة معقولة لكل محاولة. أول نطاق
  // ينجح يكفي لاعتبار الاتصال فعلياً — هذا يقلّل من احتمال اعتبار الاتصال
  // "مقطوعاً" خطأً بسبب مشكلة بنطاق واحد بعينه بينما الإنترنت شغال فعلاً
  Future<bool> _hasActualInternetAccess() async {
    const hosts = ['google.com', 'cloudflare.com', 'apple.com'];

    for (final host in hosts) {
      try {
        final result = await InternetAddress.lookup(
          host,
        ).timeout(const Duration(seconds: 5));
        if (result.isNotEmpty && result.first.rawAddress.isNotEmpty) {
          return true;
        }
      } on SocketException {
        continue; // جرّب النطاق التالي
      } on TimeoutException {
        continue; // جرّب النطاق التالي
      } catch (_) {
        continue; // جرّب النطاق التالي
      }
    }

    return false; // فشلت كل المحاولات فعلاً
  }

  @override
  void dispose() {
    _pulseController.dispose();
    _rotateController.dispose();
    _connectivitySub?.cancel();
    _internetCheckTimer?.cancel();
    super.dispose();
  }

  Future<void> _loadAndroidVersion() async {
    try {
      final int v = await _channel.invokeMethod('getAndroidVersion');
      setState(() => _androidVersion = v);
    } catch (_) {}
  }

  Future<void> _checkInitialStatus() async {
    try {
      final bool status = await _channel.invokeMethod('isMonitoring');
      setState(() => _isActive = status);
    } catch (e) {
      debugPrint("خطأ: $e");
    }
  }

  Future<void> _toggleProtection() async {
    try {
      if (_isActive) {
        await _channel.invokeMethod('stopMonitoring');
      } else {
        await _channel.invokeMethod('startMonitoring');
        await _pushCurrentSettings();
      }
      await Future.delayed(const Duration(milliseconds: 600));
      await _checkInitialStatus();
    } on PlatformException catch (e) {
      debugPrint("PlatformException: ${e.message}");
    }
  }

  Future<void> _pushCurrentSettings() async {
    // ✅ يُرسَل أولاً حتى تعرف الخدمة الأصلية (Native) أي محلل تستخدم
    // قبل بدء أول التقاط فعلي للشاشة
    await _pushAnalyzerMode();

    if (_androidVersion >= 31) {
      await _channel.invokeMethod('setBlurRadius', {'radius': _blurRadius});
    } else {
      await _channel.invokeMethod('setOverlayColor', {
        'color': _selectedColor.toARGB32(),
      });
    }
  }

  // ✅ جديد: إرسال وضع المحلل (محلي/احترافي) إلى الجهة الأصلية (Native)
  Future<void> _pushAnalyzerMode() async {
    try {
      await _channel.invokeMethod('setAnalyzerMode', {
        'useLocalAi': _useLocalAi,
      });
    } catch (e) {
      debugPrint("خطأ في إرسال وضع المحلل: $e");
    }
  }

  // ✅ جديد: توست مصمَّم بنفس هوية التطبيق (ذهبي على خلفية غامقة) بدل الشكل
  // الافتراضي — يظهر أسفل الشاشة عند محاولة اختيار "احترافي" بدون إنترنت
  void _showNoInternetToast() {
    showToastWidget(
      Container(
        margin: const EdgeInsets.symmetric(horizontal: 24, vertical: 8),
        padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 14),
        decoration: BoxDecoration(
          color: _surface,
          borderRadius: BorderRadius.circular(14),
          border: Border.all(color: _gold.withValues(alpha: 0.5), width: 1.2),
          boxShadow: [
            BoxShadow(
              color: Colors.black.withValues(alpha: 0.35),
              blurRadius: 14,
              offset: const Offset(0, 4),
            ),
          ],
        ),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            Container(
              padding: const EdgeInsets.all(8),
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                color: _gold.withValues(alpha: 0.12),
              ),
              child: Icon(Icons.wifi_off_rounded, color: _goldLight, size: 20),
            ),
            const SizedBox(width: 12),
            Flexible(
              child: Text(
                'لا يتوفر اتصال بالإنترنت',
                style: TextStyle(
                  color: _textMain,
                  fontSize: 14,
                  fontWeight: FontWeight.w600,
                  fontFamily: 'serif',
                ),
              ),
            ),
          ],
        ),
      ),
      context: context,
      animation: StyledToastAnimation.slideFromBottomFade,
      reverseAnimation: StyledToastAnimation.slideToBottomFade,
      position: StyledToastPosition.bottom,
      startOffset: const Offset(0.0, 3.0),
      reverseEndOffset: const Offset(0.0, 3.0),
      duration: const Duration(seconds: 3),
      animDuration: const Duration(milliseconds: 350),
      curve: Curves.easeOutBack,
      reverseCurve: Curves.easeIn,
    );
  }

  String get _blurLabel {
    if (_blurRadius <= 6) return 'خفيف';
    if (_blurRadius <= 13) return 'متوسط';
    if (_blurRadius <= 20) return 'قوي';
    return 'أقصى';
  }

  // ── ألوان الثيم الإسلامي ────────────────────────────────────────────────────
  static const Color _gold = Color(0xFFB8960C);
  static const Color _goldDim = Color(0xFF8A6A00);
  static const Color _goldLight = Color(0xFFD4AF37);
  static const Color _bg = Color(0xFF0C0E0B);
  static const Color _surface = Color(0xFF141810);
  static const Color _surface2 = Color(0xFF1C2118);
  static const Color _textMain = Color(0xFFEDE8D0);
  static const Color _textSub = Color(0xFF8A8670);

  @override
  Widget build(BuildContext context) {
    return Directionality(
      textDirection: TextDirection.rtl,
      child: Scaffold(
        backgroundColor: _bg,
        body: Stack(
          children: [
            // ── خلفية زخرفية دوّارة ──────────────────────────────────────────
            Positioned.fill(
              child: IslamicBackground(controller: _rotateController),
            ),

            // ── المحتوى ───────────────────────────────────────────────────────
            SafeArea(
              child: Column(
                children: [
                  _buildAppBar(),
                  Expanded(
                    child: SingleChildScrollView(
                      padding: const EdgeInsets.symmetric(horizontal: 20),
                      child: Column(
                        children: [
                          const SizedBox(height: 24),
                          _buildStatusWidget(),
                          const SizedBox(height: 28),
                          _buildAyah(),
                          const SizedBox(height: 24),
                          _buildCustomizationPanel(),
                          const SizedBox(height: 24),
                          _buildToggleButton(),
                          const SizedBox(height: 32),
                        ],
                      ),
                    ),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  // ── AppBar ─────────────────────────────────────────────────────────────────
  Widget _buildAppBar() {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 14),
      decoration: BoxDecoration(
        color: _surface.withValues(alpha: 0.95),
        border: Border(
          bottom: BorderSide(color: _gold.withValues(alpha: 0.3), width: 1),
        ),
      ),
      child: Row(
        children: [
          // زخرفة يسار
          GoldOrnament(size: 22),
          const Spacer(),
          // العنوان
          Column(
            children: [
              Text(
                'غُضُّوا',
                style: TextStyle(
                  fontFamily: 'serif',
                  fontSize: 26,
                  fontWeight: FontWeight.bold,
                  color: _goldLight,
                  letterSpacing: 2,
                  shadows: [
                    Shadow(color: _gold.withValues(alpha: 0.5), blurRadius: 12),
                  ],
                ),
              ),
              Text(
                ' تطبيق مساعد على غض البصر',
                style: TextStyle(
                  fontSize: 11,
                  color: _textSub,
                  letterSpacing: 3,
                ),
              ),
            ],
          ),
          const Spacer(),
          // زخرفة يمين
          GoldOrnament(size: 22, mirror: true),
        ],
      ),
    );
  }

  // ── ويدجت الحالة ──────────────────────────────────────────────────────────
  Widget _buildStatusWidget() {
    final bool active = _isActive;
    return AnimatedBuilder(
      animation: _pulseAnim,
      builder: (context, child) {
        return Transform.scale(
          scale: active ? _pulseAnim.value : 1.0,
          child: child,
        );
      },
      child: Container(
        width: double.infinity,
        padding: const EdgeInsets.symmetric(vertical: 28, horizontal: 20),
        decoration: BoxDecoration(
          color: _surface,
          borderRadius: BorderRadius.circular(20),
          border: Border.all(
            color:
                active
                    ? _gold.withValues(alpha: 0.6)
                    : _textSub.withValues(alpha: 0.2),
            width: 1.5,
          ),
          boxShadow:
              active
                  ? [
                    BoxShadow(
                      color: _gold.withValues(alpha: 0.15),
                      blurRadius: 24,
                      spreadRadius: 2,
                    ),
                  ]
                  : [],
        ),
        child: Column(
          children: [
            // أيقونة العين
            Stack(
              alignment: Alignment.center,
              children: [
                if (active)
                  Container(
                    width: 90,
                    height: 90,
                    decoration: BoxDecoration(
                      shape: BoxShape.circle,
                      color: _gold.withValues(alpha: 0.08),
                    ),
                  ),
                Container(
                  width: 72,
                  height: 72,
                  decoration: BoxDecoration(
                    shape: BoxShape.circle,
                    color: active ? _gold.withValues(alpha: 0.15) : _surface2,
                    border: Border.all(
                      color: active ? _gold : _textSub.withValues(alpha: 0.3),
                      width: 1.5,
                    ),
                  ),
                  child: Icon(
                    active ? Icons.visibility : Icons.visibility_off,
                    color: active ? _goldLight : _textSub,
                    size: 32,
                  ),
                ),
              ],
            ),
            const SizedBox(height: 16),
            Text(
              active ? 'الحماية نشطة' : 'الحماية متوقفة',
              style: TextStyle(
                fontSize: 20,
                fontWeight: FontWeight.bold,
                color: active ? _goldLight : _textSub,
                fontFamily: 'serif',
              ),
            ),
            const SizedBox(height: 6),
            Text(
              active
                  ? 'يتم تحليل الشاشة بالذكاء الاصطناعي المحلي'
                  : 'اضغط لتفعيل نظام الحماية',
              style: TextStyle(fontSize: 12, color: _textSub),
              textAlign: TextAlign.center,
            ),
          ],
        ),
      ),
    );
  }

  // ──  بطاقتا اختيار وضع المحلل: محلي / احترافي (سحابي) ──────────────────────
  Widget _buildAyah() {
    final bool isLocal = _useLocalAi;
    final bool isOnline = !_useLocalAi;
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 18),
      decoration: BoxDecoration(
        color: _surface,
        borderRadius: BorderRadius.circular(16),
        border: Border.all(color: _gold.withValues(alpha: 0.2), width: 1),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // ── العنوان ──────────────────────────────────────────────────────
          Row(
            children: [
              Icon(Icons.memory_outlined, color: _gold, size: 16),
              const SizedBox(width: 8),
              Text(
                'وضع المحلل',
                style: TextStyle(
                  fontSize: 14,
                  fontWeight: FontWeight.bold,
                  color: _textMain,
                ),
              ),
              const Spacer(),
            ],
          ),

          const SizedBox(height: 16),

          // ── بطاقتا الاختيار ───────────────────────────────────────────
          Row(
            children: [
              // ── محلي ──────────────────────────────────────────────────
              Expanded(
                child: ModeCard(
                  selected: isLocal,
                  enabled: true,
                  icon: Icons.smartphone_outlined,
                  title: 'محلي',
                  subtitle: 'خصوصية كاملة',
                  onTap: () async {
                    if (_useLocalAi) return; // محدد بالفعل
                    setState(() => _useLocalAi = true);
                    await _pushAnalyzerMode();
                  },
                ),
              ),
              const SizedBox(width: 10),
              // ── احترافي (سحابي) ─────────────────────────────────────────
              // ✅ ملاحظة: نضع GestureDetector شفاف فوق البطاقة بالكامل
              // (Positioned.fill) بدل الاعتماد فقط على onTap الداخلي لـ
              // ModeCard. لأن ModeCard على الأغلب توقف استقبال اللمس داخلياً
              // بنفسها لما enabled=false (زي AbsorbPointer/IgnorePointer)،
              // وهذا كان يمنع تنفيذ منطقنا (عرض التوست) من الأساس بعد أول
              // مرة تتعطّل فيها البطاقة. الطبقة العلوية هنا تستقبل كل ضغطة
              // بشكل مستقل تماماً عن الحالة الداخلية لـ ModeCard، فيضمن ظهور
              // التوست في كل مرة يضغط فيها المستخدم أثناء انقطاع النت، وأيضاً
              // يصحّح _hasInternet فوراً لحظة الضغط لو تبيّن أن النت رجع
              // فعلياً (بدل انتظار الفحص الدوري كل 10 ثوانٍ).
              Expanded(
                child: Stack(
                  children: [
                    ModeCard(
                      selected: isOnline,
                      enabled: _hasInternet,
                      icon: Icons.cloud_outlined,
                      title: 'احترافي',
                      subtitle: _hasInternet ? 'دقة أعلى' : 'يتطلب إنترنت',
                      onTap: _onTapProfessionalMode,
                    ),
                    Positioned.fill(
                      child: GestureDetector(
                        behavior: HitTestBehavior.opaque,
                        onTap: _onTapProfessionalMode,
                      ),
                    ),
                  ],
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }

  // ✅ جديد: معالج ضغط موحّد لبطاقة "احترافي" — يُستدعى من الطبقة الشفافة
  // العلوية دائماً بغض النظر عن الحالة الداخلية لـ ModeCard، فيضمن:
  // 1) ظهور توست "لا يتوفر اتصال بالإنترنت" في كل مرة يُضغط فيها والنت
  //    فعلياً مقطوع (مو مرة وحدة بس).
  // 2) تصحيح _hasInternet فوراً لحظة الضغط بالاتجاهين (لو النت رجع فعلياً
  //    لكن الفحص الدوري لسا ما وصل، ولو انقطع لتوّه) — بدل انتظار المؤقت
  //    الدوري (10 ثوانٍ) أو حدث تغيّر الواجهة اللي قد يتأخر أو ما يوصل.
  Future<void> _onTapProfessionalMode() async {
    final actuallyOnline = await _hasActualInternetAccess();

    if (!actuallyOnline) {
      if (mounted && _hasInternet) {
        setState(() => _hasInternet = false);
      }
      _showNoInternetToast();
      return;
    }

    // النت فعلاً متوفر الآن — تصحيح الحالة فوراً حتى لو كانت لا تزال
    // مسجَّلة كمنقطعة من فحص سابق
    if (mounted && !_hasInternet) {
      setState(() => _hasInternet = true);
    }

    if (!_useLocalAi) return; // محدد بالفعل
    setState(() => _useLocalAi = false);
    await _pushAnalyzerMode();
  }

  Widget goldDivider() => Expanded(
    child: Container(height: 1, color: _gold.withValues(alpha: 0.25)),
  );

  // ── لوحة التخصيص ──────────────────────────────────────────────────────────
  Widget _buildCustomizationPanel() {
    if (_androidVersion >= 31) {
      // Android 12+: شدة الـ blur
      return IslamicCard(
        title: 'شدة الضبابية',
        subtitle: _blurLabel,
        icon: Icons.blur_on_outlined,
        child: Column(
          children: [
            const SizedBox(height: 12),
            SliderTheme(
              data: SliderTheme.of(context).copyWith(
                activeTrackColor: _gold,
                inactiveTrackColor: _gold.withValues(alpha: 0.15),
                thumbColor: _goldLight,
                overlayColor: _gold.withValues(alpha: 0.1),
                trackHeight: 3,
              ),
              child: Slider(
                value: _blurRadius,
                min: 1.0,
                max: 25.0,
                divisions: 24,
                label: _blurRadius.toInt().toString(),
                onChanged: (v) => setState(() => _blurRadius = v),
                onChangeEnd: (_) => _pushCurrentSettings(),
              ),
            ),
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 8),
              child: Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  Text('خفيف', style: TextStyle(fontSize: 11, color: _textSub)),
                  Text(
                    'متوسط',
                    style: TextStyle(fontSize: 11, color: _textSub),
                  ),
                  Text('أقصى', style: TextStyle(fontSize: 11, color: _textSub)),
                ],
              ),
            ),
            const SizedBox(height: 14),
            // ── معاينة الـ blur على الصورة الحقيقية ──────────────────────────
            ClipRRect(
              borderRadius: BorderRadius.circular(10),
              child: Stack(
                alignment: Alignment.center,
                children: [
                  // الصورة الأصلية
                  Image(
                    image: const AssetImage(ImageAppResources.blurTest),
                    height: 100,
                    width: double.infinity,
                    fit: BoxFit.cover,
                  ),
                  // طبقة ضبابية محاكاة بـ BackdropFilter
                  Positioned.fill(
                    child: BackdropFilter(
                      filter: ImageFilter.blur(
                        sigmaX: _blurRadius * 0.6,
                        sigmaY: _blurRadius * 0.6,
                      ),
                      child: Container(color: Colors.transparent),
                    ),
                  ),
                  // نص معاينة
                  Container(
                    padding: const EdgeInsets.symmetric(
                      horizontal: 10,
                      vertical: 4,
                    ),
                    decoration: BoxDecoration(
                      color: Colors.black45,
                      borderRadius: BorderRadius.circular(6),
                    ),
                    child: Text(
                      'معاينة الضبابية',
                      style: TextStyle(color: Colors.white70, fontSize: 11),
                    ),
                  ),
                ],
              ),
            ),
          ],
        ),
      );
    }

    // Android 11-: اختيار اللون
    return IslamicCard(
      title: 'لون شاشة الحجب',
      subtitle:
          _colorOptions
              .firstWhere(
                (c) => c.color == _selectedColor,
                orElse: () => _colorOptions.first,
              )
              .name,
      icon: Icons.palette_outlined,
      child: Column(
        children: [
          const SizedBox(height: 16),
          Wrap(
            spacing: 14,
            runSpacing: 14,
            alignment: WrapAlignment.center,
            children:
                _colorOptions.map((opt) {
                  final bool sel = _selectedColor == opt.color;
                  return GestureDetector(
                    onTap: () async {
                      setState(() => _selectedColor = opt.color);
                      await _pushCurrentSettings();
                    },
                    child: Column(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        AnimatedContainer(
                          duration: const Duration(milliseconds: 250),
                          width: 46,
                          height: 46,
                          decoration: BoxDecoration(
                            color: opt.color,
                            shape: BoxShape.circle,
                            border: Border.all(
                              color: sel ? _goldLight : Colors.white12,
                              width: sel ? 2.5 : 1,
                            ),
                            boxShadow:
                                sel
                                    ? [
                                      BoxShadow(
                                        color: _gold.withValues(alpha: 0.4),
                                        blurRadius: 10,
                                      ),
                                    ]
                                    : [],
                          ),
                          child:
                              sel
                                  ? Icon(
                                    Icons.check,
                                    color: _goldLight,
                                    size: 18,
                                  )
                                  : null,
                        ),
                        const SizedBox(height: 5),
                        Text(
                          opt.name,
                          style: TextStyle(
                            fontSize: 10,
                            color: sel ? _goldLight : _textSub,
                          ),
                        ),
                      ],
                    ),
                  );
                }).toList(),
          ),
          const SizedBox(height: 16),
          // معاينة
          ClipRRect(
            borderRadius: BorderRadius.circular(10),
            child: Container(
              height: 56,
              color: _surface2,
              padding: const EdgeInsets.all(8),
              child: Container(
                decoration: BoxDecoration(
                  color: _selectedColor,
                  borderRadius: BorderRadius.circular(6),
                ),
                child: Center(
                  child: Text(
                    'معاينة الحجب',
                    style: TextStyle(color: Colors.white38, fontSize: 12),
                  ),
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildToggleButton() {
    return GestureDetector(
      onTap: _toggleProtection,
      child: AnimatedContainer(
        duration: const Duration(milliseconds: 400),
        width: double.infinity,
        height: 58,
        decoration: BoxDecoration(
          borderRadius: BorderRadius.circular(16),
          color: _isActive ? const Color(0xFF1A0A0A) : _surface,
          border: Border.all(
            color:
                _isActive
                    ? Colors.red.shade900.withValues(alpha: 0.6)
                    : _gold.withValues(alpha: 0.5),
            width: 1.5,
          ),
          boxShadow: [
            BoxShadow(
              color:
                  _isActive
                      ? Colors.red.withValues(alpha: 0.15)
                      : _gold.withValues(alpha: 0.15),
              blurRadius: 16,
            ),
          ],
        ),
        child: Row(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Icon(
              _isActive
                  ? Icons.stop_circle_outlined
                  : Icons.play_circle_outline,
              color: _isActive ? Colors.red.shade400 : _goldLight,
              size: 24,
            ),
            const SizedBox(width: 10),
            Text(
              _isActive ? 'إيقاف الحماية' : 'تفعيل الحماية',
              style: TextStyle(
                fontSize: 17,
                fontWeight: FontWeight.bold,
                color: _isActive ? Colors.red.shade400 : _goldLight,
                fontFamily: 'serif',
                letterSpacing: 1,
              ),
            ),
          ],
        ),
      ),
    );
  }
}