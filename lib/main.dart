import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:safety_screen/screens/onboarding_screen.dart';
import 'package:safety_screen/screens/home_screen.dart';
import 'package:safety_screen/screens/splash_screen.dart';
import 'package:shared_preferences/shared_preferences.dart';


void main() async {

  WidgetsFlutterBinding.ensureInitialized();
  
  SystemChrome.setSystemUIOverlayStyle(const SystemUiOverlayStyle(
    statusBarColor:            Colors.transparent,
    statusBarIconBrightness:   Brightness.light,
  ));


  final prefs =await SharedPreferences.getInstance();
  final bool isFirstTime = prefs.getBool('isFirstTime') ?? true;

  runApp(SafeScreenApp(isFirstTime: isFirstTime));
}

class SafeScreenApp extends StatelessWidget {
  final bool isFirstTime;
  const SafeScreenApp({super.key, required this.isFirstTime});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title:'غُضُّوا',
      debugShowCheckedModeBanner: true,
      theme: ThemeData(
        colorScheme: ColorScheme.dark(
          primary:   const Color(0xFFD4AF37), 
          secondary: const Color(0xFF1A3C2A), 
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
      home: _SplashWrapper(isFirstTime: isFirstTime),
    );
  }
}

class _SplashWrapper extends StatefulWidget {
  final bool isFirstTime;
  const _SplashWrapper({required this.isFirstTime});

  @override
  State<_SplashWrapper> createState() => _SplashWrapperState();
}

class _SplashWrapperState extends State<_SplashWrapper> {
  Widget? _nextScreen;

  void _onSplashComplete() {
    setState(() {

      if (widget.isFirstTime) {
        _nextScreen = const OnboardingScreen();
      } else {
        _nextScreen = const HomeScreen();
      }
    });
  }

  @override
  Widget build(BuildContext context) {

    if (_nextScreen != null) {
      return _nextScreen!;
    }

    return SplashScreen(onComplete: _onSplashComplete);
  }
}