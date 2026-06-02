import 'package:flutter/material.dart';
import 'ui/home_screen.dart';

void main() => runApp(MyApp());

class MyApp extends StatelessWidget {
  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'WebRTC VPN',
      home: HomeScreen(),
    );
  }
}