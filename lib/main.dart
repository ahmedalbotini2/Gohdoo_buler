import 'package:flutter/material.dart';
import 'package:safety_screen/home.dart';


void main() {
  runApp(const SafeScreenApp());
}

class SafeScreenApp extends StatelessWidget {
  const SafeScreenApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'SafeScreen',
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: Colors.deepPurple),
        useMaterial3: true,
      ),
      home: const SafeScreenHome(),
    );
  }
}