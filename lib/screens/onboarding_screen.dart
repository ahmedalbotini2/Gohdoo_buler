import 'package:flutter/material.dart';
import 'package:safety_screen/res/resourse.dart';
import 'package:safety_screen/widgets/feature_box.dart';
import 'package:safety_screen/themes/intro_background_painter.dart';
import 'package:safety_screen/widgets/terms_bullet.dart';
import 'package:safety_screen/screens/home_screen.dart';
import 'package:shared_preferences/shared_preferences.dart'; // تأكد من مسار الاستيراد الصحيح
// ستحتاج إلى إضافة حزمة shared_preferences في pubspec.yaml لاحقاً لحفظ حالة العرض الأول

class OnboardingScreen extends StatefulWidget {
  const OnboardingScreen({super.key});

  @override
  State<OnboardingScreen> createState() => _OnboardingScreenState();
}

class _OnboardingScreenState extends State<OnboardingScreen> with SingleTickerProviderStateMixin {
  final PageController _pageController = PageController();
  int _currentPage = 0;
  bool _acceptedTerms = false;
  
  late AnimationController _bgRotateCtrl;


  static const Color _bg        = Color(0xFF0C0E0B);
  static const Color _surface   = Color(0xFF141810);
  static const Color _surface2  = Color(0xFF1C2118);
  static const Color _gold      = Color(0xFFB8960C);
  static const Color _goldLight = Color(0xFFD4AF37);
  static const Color _textMain  = Color(0xFFEDE8D0);
  static const Color _textSub   = Color(0xFF8A8670);

  @override
  void initState() {
    super.initState();
    _bgRotateCtrl = AnimationController(
      vsync: this,
      duration: const Duration(seconds: 40),
    )..repeat();
  }

  @override
  void dispose() {
    _pageController.dispose();
    _bgRotateCtrl.dispose();
    super.dispose();
  }

  void _nextPage() {
    if (_currentPage < 3) {
      _pageController.nextPage(
        duration: const Duration(milliseconds: 500),
        curve: Curves.easeInOut,
      );
    }
  }

  void _finishOnboarding() async {
    if (!_acceptedTerms) return;
    
    // ملاحظة: هنا يمكنك استخدام SharedPreferences لحفظ أن المستخدم أكمل الشاشات
    // مثال:
    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool('isFirstTime', false);

    if (mounted) {
      Navigator.of(context).pushReplacement(
        MaterialPageRoute(builder: (_) => const HomeScreen()),
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    return Directionality(
      textDirection: TextDirection.rtl,
      child: Scaffold(
        backgroundColor: _bg,
        body: Stack(
          children: [
            // خلفية زخرفية
            Positioned.fill(
              child: AnimatedBuilder(
                animation: _bgRotateCtrl,
                builder: (_, __) => CustomPaint(
                  painter: IntroBackgroundPainter(_bgRotateCtrl.value, _gold),
                ),
              ),
            ),
            
            SafeArea(
              child: Column(
                children: [
                  // زر التخطي
                  Align(
                    alignment: Alignment.topRight,
                    child: TextButton(
                      onPressed: _currentPage == 3 ? null : () => _pageController.jumpToPage(3),
                      child: Text(
                        _currentPage == 3 ? '' : 'تخطي',
                        style: const TextStyle(color: _textSub, fontFamily: 'serif'),
                      ),
                    ),
                  ),
                  
                  // محتوى الصفحات
                  Expanded(
                    child: PageView(
                      controller: _pageController,
                      onPageChanged: (index) {
                        setState(() => _currentPage = index);
                      },
                      children: [
                        _buildIntroPage(),
                        _buildLocalAiPage(),
                        _buildProAiPage(),
                        _buildTermsPage(),
                      ],
                    ),
                  ),
                  
                  // شريط التنقل السفلي
                  _buildBottomNavigation(),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  // ── 1. صفحة التعريف بالتطبيق ───────────────────────────────────────────────
  Widget _buildIntroPage() {
    return _buildPageTemplate(
      image: Image.asset(ImageAppResources.onboarding),
      title: 'مرحباً بك في غُضُّوا',
      description: 'تطبيقك المساعد لغض البصر. نستخدم تقنيات الذكاء الاصطناعي لتحليل الشاشة لحظياً وحجب المحتوى غير اللائق تلقائياً، لنوفر لك بيئة تصفح نقية وآمنة.',
    );
  }

  // ── 2. صفحة المعالجة المحلية ────────────────────────────────────────────────
  Widget _buildLocalAiPage() {
    return _buildFeaturePage(
      icon: Icons.smartphone_outlined,
      title: 'المعالجة المحلية',
      description: 'يتم تحليل الصور والنصوص داخل جهازك بالكامل دون إرسالها لأي خادم خارجي.',
      pros: ['خصوصية تامة 100%', 'لا تحتاج إلى اتصال بالإنترنت', 'استجابة سريعة جداً'],
      cons: ['دقة التحليل تعتبر متوسطة', 'قد تستهلك من بطارية الجهاز وموارده'],
    );
  }

  // ── 3. صفحة المعالجة الاحترافية ─────────────────────────────────────────────
  Widget _buildProAiPage() {
    return _buildFeaturePage(
      icon: Icons.cloud_done_outlined,
      title: 'المعالجة الاحترافية',
      description: 'تعتمد على خوادم سحابية متطورة لتحليل المحتوى بدقة متناهي',
      pros: ['دقة عالية جداً في الفلترة', 'فهم أعمق للمشاهد المعقدة', 'لا تستهلك موارد جهازك'],
      cons: ['تتطلب اتصالاً مستمراً بالإنترنت', 'قد يوجد تأخير بسيط جداً في الاستجابة'],
    );
  }

  // ── 4. صفحة الشروط والموافقة ────────────────────────────────────────────────
  Widget _buildTermsPage() {
    return LayoutBuilder(
      builder: (context, constraints) {
        return SingleChildScrollView(
          padding: const EdgeInsets.symmetric(horizontal: 24.0, vertical: 16.0),
          child: ConstrainedBox(
            // ✅ يضمن إن المحتوى ياخد على الأقل ارتفاع الشاشة المتاحة (عشان
            // الـ Spacer يشتغل ويدفع مربع الموافقة لأسفل زي المطلوب)، لكن
            // من غير ما يمنع التمرير لو المحتوى زاد فعليًا عن المساحة —
            // وده اللي كان بيسبب RenderFlex overflow قبل كده.
            constraints: BoxConstraints(
              minHeight: constraints.maxHeight - 32, // 32 = padding العمودي أعلاه/أسفل
            ),
            child: IntrinsicHeight(
              child: Column(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  Container(
                    padding: const EdgeInsets.all(20),
                    decoration: BoxDecoration(
                      shape: BoxShape.circle,
                      color: _gold.withValues(alpha: 0.1),
                      border: Border.all(color: _gold.withValues(alpha: 0.3), width: 1.5),
                    ),
                    child: const Icon(Icons.verified_user_outlined, size: 60, color: _goldLight),
                  ),
                  const SizedBox(height: 32),
                  const Text(
                    'شروط الاستخدام والخصوصية',
                    style: TextStyle(fontSize: 24, fontWeight: FontWeight.bold, color: _goldLight, fontFamily: 'serif'),
                    textAlign: TextAlign.center,
                  ),
                 const SizedBox(height: 16),
                  Container(
                    padding: const EdgeInsets.all(16),
                    decoration: BoxDecoration(
                      color: _surface,
                      borderRadius: BorderRadius.circular(16),
                      border: Border.all(color: _gold.withValues(alpha: 0.2)),
                    ),
                    child:  Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        TermsBullet(text: 'خصوصيتك هي أولويتنا القصوى.'),
                        TermsBullet(text: 'التطبيق لا يقوم بحفظ، تخزين، أو مشاركة أي محتوى يظهر على شاشتك.'),
                        TermsBullet(text: 'لا يتم استغلال بياناتك الشخصية أو بيعها لأي جهة خارجية تحت أي ظرف.'),
                        TermsBullet(text: 'نظام الفلترة المحلي يعمل بالكامل داخل هاتفك.'),
                      ],
                    ),
                  ),
                  const Spacer(),
                  GestureDetector(
                    onTap: () {
                      setState(() => _acceptedTerms = !_acceptedTerms);
                    },
                    child: Row(
                      children: [
                        AnimatedContainer(
                          duration: const Duration(milliseconds: 200),
                          width: 24, height: 24,
                          decoration: BoxDecoration(
                            color: _acceptedTerms ? _gold : Colors.transparent,
                            borderRadius: BorderRadius.circular(6),
                            border: Border.all(
                              color: _acceptedTerms ? _gold : _textSub,
                              width: 1.5,
                            ),
                          ),
                          child: _acceptedTerms ? const Icon(Icons.check, size: 16, color: _bg) : null,
                        ),
                        const SizedBox(width: 12),
                        const Expanded(
                          child: Text(
                            'قرأت الشروط وأوافق على سياسة الخصوصية وعدم استغلال البيانات.',
                            style: TextStyle(color: _textMain, fontSize: 13, height: 1.5),
                          ),
                        ),
                      ],
                    ),
                  ),
                  const SizedBox(height: 20),
                ],
              ),
            ),
          ),
        );
      },
    );
  }

  // ── قوالب مساعدة لبناء الواجهة ─────────────────────────────────────────────
  
  Widget _buildPageTemplate({ Image? image,IconData? icon, required String title, required String description}) {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 32.0),
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
         icon != null 
    ? Container(
        // تصميم الأيقونة (كما هو)
        padding: const EdgeInsets.all(24),
        decoration: BoxDecoration(
          shape: BoxShape.circle,
          color: _gold.withValues(alpha: 0.08),
          border: Border.all(color: _gold.withValues(alpha: 0.3), width: 1.5),
        ),
        child: Icon(icon, size: 80, color: _goldLight),
      )
    : SizedBox(
        // تصميم الصورة (بدون خلفية وبنفس الحجم الإجمالي)
        width: 128, 
        height: 128,
        child: Image.asset(
          ImageAppResources.onboarding,
          fit: BoxFit.contain, // يمكنك تغييرها إلى BoxFit.cover إذا أردت ملء المساحة بالكامل
        ),
      ),
          const SizedBox(height: 40),
          Text(
            title,
            style: const TextStyle(fontSize: 26, fontWeight: FontWeight.bold, color: _goldLight, fontFamily: 'serif'),
            textAlign: TextAlign.center,
          ),
          const SizedBox(height: 20),
          Text(
            description,
            style: const TextStyle(fontSize: 15, color: _textMain, height: 1.7),
            textAlign: TextAlign.center,
          ),
        ],
      ),
    );
  }

  Widget _buildFeaturePage({
    required IconData icon,
    required String title,
    required String description,
    required List<String> pros,
    required List<String> cons,
  }) {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 24.0, vertical: 16.0),
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          Icon(icon, size: 50, color: _goldLight),
          const SizedBox(height: 16),
          Text(
            title,
            style: const TextStyle(fontSize: 22, fontWeight: FontWeight.bold, color: _goldLight, fontFamily: 'serif'),
            textAlign: TextAlign.center,
          ),
          const SizedBox(height: 12),
          Text(
            description,
            style: const TextStyle(fontSize: 13, color: _textMain, height: 1.5),
            textAlign: TextAlign.center,
          ),
          const SizedBox(height: 32),
          FeatureBox(title: 'المميزات', items: pros, isPro: true),
          const SizedBox(height: 16),
          FeatureBox(title: 'العيوب', items: cons, isPro: false),
        ],
      ),
    );
  }

  Widget _buildBottomNavigation() {
    return Padding(
      padding: const EdgeInsets.all(24.0),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.spaceBetween,
        children: [
          // مؤشرات الصفحات (Dots)
          Row(
            children: List.generate(
              4,
              (index) => AnimatedContainer(
                duration: const Duration(milliseconds: 300),
                margin: const EdgeInsets.only(left: 6),
                height: 6,
                width: _currentPage == index ? 24 : 6,
                decoration: BoxDecoration(
                  color: _currentPage == index ? _goldLight : _surface2,
                  borderRadius: BorderRadius.circular(3),
                ),
              ),
            ),
          ),
          

          AnimatedSwitcher(
            duration: const Duration(milliseconds: 300),
            child: _currentPage == 3
                ? ElevatedButton(
                    key: const ValueKey('startBtn'),
                    onPressed: _acceptedTerms ? _finishOnboarding : null,
                    style: ElevatedButton.styleFrom(
                      backgroundColor: _gold,
                      disabledBackgroundColor: _surface2,
                      foregroundColor: _bg,
                      disabledForegroundColor: _textSub,
                      padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 12),
                      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
                    ),
                    child: const Text('موافق ومتابعة', style: TextStyle(fontWeight: FontWeight.bold, fontFamily: 'serif')),
                  )
                : InkWell(
                    key: const ValueKey('nextBtn'),
                    onTap: _nextPage,
                    borderRadius: BorderRadius.circular(12),
                    child: Container(
                      padding: const EdgeInsets.all(12),
                      decoration: BoxDecoration(
                        color: _surface,
                        borderRadius: BorderRadius.circular(12),
                        border: Border.all(color: _gold.withValues(alpha: 0.3)),
                      ),
                      child: const Icon(Icons.arrow_back_ios_new, color: _goldLight, size: 20),
                    ),
                  ),
          ),
        ],
      ),
    );
  }
}