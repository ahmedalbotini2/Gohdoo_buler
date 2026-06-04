import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:safety_screen/home.dart';
import 'package:safety_screen/splash_screen.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  // شريط الحالة شفاف متناسق مع الثيم الداكن
  SystemChrome.setSystemUIOverlayStyle(const SystemUiOverlayStyle(
    statusBarColor:            Colors.transparent,
    statusBarIconBrightness:   Brightness.light,

  ));
  runApp(const SafeScreenApp());
}

class SafeScreenApp extends StatelessWidget {
  const SafeScreenApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title:        'غُضُّوا',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        colorScheme: ColorScheme.dark(
          primary:   const Color(0xFFD4AF37), // ذهبي
          secondary: const Color(0xFF1A3C2A), // أخضر الجنة
          surface:   const Color(0xFF141810),
        ),
        scaffoldBackgroundColor: const Color(0xFF0C0E0B),
        appBarTheme: const AppBarTheme(
          backgroundColor:    Color(0xFF141810),
          foregroundColor:    Color(0xFFD4AF37),
          elevation:          0,
          centerTitle:        true,
        ),
        useMaterial3: true,
      ),
      home: const _SplashWrapper(),
    );
  }
}

class _SplashWrapper extends StatefulWidget {
  const _SplashWrapper();

  @override
  State<_SplashWrapper> createState() => _SplashWrapperState();
}

class _SplashWrapperState extends State<_SplashWrapper> {
  bool _showHome = false;

  void _onSplashComplete() {
    setState(() => _showHome = true);
  }

  @override
  Widget build(BuildContext context) {
    if (_showHome) {
      return const SafeScreenHome();
    }
    return GhuddooSplash(onComplete: _onSplashComplete);
  }
}