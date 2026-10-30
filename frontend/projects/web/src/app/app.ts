import { Component } from '@angular/core';
import { RouterOutlet } from '@angular/router';

import { BrandHeader } from '@foodstack/ui';

@Component({
  imports: [RouterOutlet, BrandHeader],
  selector: 'app-root',
  styleUrl: './app.scss',
  templateUrl: './app.html',
})
export class App {}
