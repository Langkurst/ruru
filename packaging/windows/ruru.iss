; Ruru's Windows installer (Inno Setup 6). Builds Ruru-<version>-setup.exe from the release app folder.
;
;   iscc /DAppVersion=0.1.3 packaging\windows\ruru.iss
;
; Optional defines: /DPayloadDir=<the app folder that holds Ruru.exe>, /DOutDir=<where the setup goes>.
;
; Installs for the current user only (no admin prompt) into %LOCALAPPDATA%\Programs\Ruru, adds a Start menu
; shortcut and a Settings > Apps entry, and removes an older per-user MSI copy of Ruru first so nobody
; ends up with two. The window is drawn by the script below to match docs/DESIGN.md: a borderless dark
; window with the owl, a smooth bar and a percentage. Silent use (the updater, CI):
;
;   Ruru-<version>-setup.exe /VERYSILENT /SUPPRESSMSGBOXES /NORESTART [/RELAUNCH]
;
; /RELAUNCH starts Ruru once the files are in place. /DESKTOPICON also adds a desktop shortcut (none by
; default). /DRYRUNMSI (for testing) only logs which MSI copy would be removed; never pass it in a release.
; Add /LOG="<file>" to see the log.
;
; Building needs a short path: ISCC fails on files deeper than 260 characters (the CI checkout is fine).

#ifndef AppVersion
  #define AppVersion "0.1.3"
#endif
#ifndef PayloadDir
  #define PayloadDir "..\..\app\desktop\build\compose\binaries\main-release\app\Ruru"
#endif
#ifndef OutDir
  #define OutDir "..\..\app\desktop\build\compose\binaries\main-release\setup"
#endif
#define FontDir "..\..\app\shared\src\commonMain\composeResources\font"

[Setup]
; Fixed for good: it ties updates and the uninstall entry to the same program.
AppId={{7C1F3E52-9B0D-4E7A-8A63-5D2E1B94C0AF}
AppName=Ruru
AppVersion={#AppVersion}
AppVerName=Ruru {#AppVersion}
AppPublisher=Langkurst
AppPublisherURL=https://ruru-app.pages.dev
AppSupportURL=https://github.com/Langkurst/ruru/issues
AppCopyright=Copyright (c) 2026 Langkurst and Earshot contributors. MIT licence.
VersionInfoVersion={#AppVersion}.0
VersionInfoProductName=Ruru
VersionInfoProductVersion={#AppVersion}
VersionInfoCompany=Langkurst
VersionInfoDescription=Ruru setup
DefaultDirName={localappdata}\Programs\Ruru
PrivilegesRequired=lowest
PrivilegesRequiredOverridesAllowed=
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
DisableProgramGroupPage=yes
DisableDirPage=yes
DisableReadyPage=yes
DisableWelcomePage=yes
DisableReadyMemo=yes
DirExistsWarning=no
UsePreviousAppDir=yes
ShowLanguageDialog=no
CloseApplications=force
CloseApplicationsFilter=Ruru.exe
RestartApplications=no
SetupIconFile=..\..\art\ruru.ico
UninstallDisplayIcon={app}\Ruru.exe
UninstallDisplayName=Ruru
OutputDir={#OutDir}
OutputBaseFilename=Ruru-{#AppVersion}-setup
Compression=lzma2/ultra64
SolidCompression=yes
WizardStyle=modern
SetupLogging=no

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Files]
; Only the setup window uses these three: the owl and the two Mona Sans weights. They come first so
; the solid archive hands them over at once, before the window shows.
Source: "owl.bmp"; Flags: dontcopy
Source: "{#FontDir}\monasans_regular.ttf"; Flags: dontcopy
Source: "{#FontDir}\monasans_semibold.ttf"; Flags: dontcopy
Source: "{#PayloadDir}\*"; DestDir: "{app}"; Flags: recursesubdirs createallsubdirs ignoreversion

[Icons]
Name: "{autoprograms}\Ruru"; Filename: "{app}\Ruru.exe"
; Off unless asked for with /DESKTOPICON (the setup shows no choices).
Name: "{autodesktop}\Ruru"; Filename: "{app}\Ruru.exe"; Check: DesktopIconRequested

[Run]
; Only for /RELAUNCH (the updater). A person installing by hand uses the Open Ruru button instead.
Filename: "{app}\Ruru.exe"; Description: "Open Ruru"; Flags: nowait postinstall runasoriginaluser; Check: RelaunchRequested

[UninstallDelete]
; Anything Ruru wrote next to itself (caches, logs) goes too, so the folder really disappears.
Type: filesandordirs; Name: "{app}"

[Code]
const
  { Colours are BGR: #0B0C0A is $0A0C0B. See docs/DESIGN.md. }
  clNight = $0A0C0B;
  clEdge  = $1E1F1E;   { white 8% over night }
  clTrack = $191B1A;   { white 6% over night }
  clEye   = $47C5E8;   { #E8C547 }
  clInk   = $EEF3F2;   { #F2F3EE }
  clInk3  = $818483;   { ink at 52% over night }
  clBtnHot = $D6DBDA;  { the button, a touch dimmer under the pointer }
  UpgradeCode = '{6f1d1c0e-5a8b-4b8e-9d55-2f4b7a3c9e21}';
  FR_PRIVATE = $10;
  WM_NCLBUTTONDOWN = $00A1;
  BM_CLICK = $00F5;
  HTCAPTION = 2;
  BarW = 396;
  BarH = 6;
  BtnW = 104;
  BtnH = 36;

type
  TPt = record
    X, Y: Integer;
  end;

function AddFontResourceEx(Name: String; Flags: DWORD; Reserved: DWORD): Integer;
  external 'AddFontResourceExW@gdi32.dll stdcall';
function CreateRoundRectRgn(L, T, R, B, W, H: Integer): THandle;
  external 'CreateRoundRectRgn@gdi32.dll stdcall';
function SetWindowRgn(Wnd: HWND; Rgn: THandle; Redraw: Boolean): Integer;
  external 'SetWindowRgn@user32.dll stdcall';
function ReleaseCapture: Boolean;
  external 'ReleaseCapture@user32.dll stdcall';
function GetCursorPos(var P: TPt): Boolean;
  external 'GetCursorPos@user32.dll stdcall';
function ScreenToClient(Wnd: HWND; var P: TPt): Boolean;
  external 'ScreenToClient@user32.dll stdcall';
function SetTimer(Wnd: HWND; Id: LongWord; Elapse: LongWord; Proc: LongWord): LongWord;
  external 'SetTimer@user32.dll stdcall';
function KillTimer(Wnd: HWND; Id: LongWord): Boolean;
  external 'KillTimer@user32.dll stdcall';
function GetAsyncKeyState(Key: Integer): Integer;
  external 'GetAsyncKeyState@user32.dll stdcall';
function MsiEnumRelatedProducts(Code: String; Reserved: DWORD; Index: DWORD; Product: String): DWORD;
  external 'MsiEnumRelatedProductsW@msi.dll stdcall';

var
  Plain: Boolean;            { true when the window is not drawn by us (silent runs) }
  Backdrop, OwlPic, BarPic, ClosePic, OpenPic: TBitmapImage;
  TitleText, SubText, NoteText, PercentText: TLabel;
  TimerId: LongWord;
  Shown: Integer;            { thousandths shown by the bar; glides toward the real progress, never back }
  Finished: Boolean;         { the finished screen is up }
  CloseHot, OpenHot, WasDown, ReadyConfirmed: Boolean;
  FontBold, FontRegular: String;

{ ---------- helpers ---------- }

function HasSwitch(const Name: String): Boolean;
var
  I: Integer;
begin
  Result := False;
  for I := 1 to ParamCount do
    if CompareText(ParamStr(I), Name) = 0 then
    begin
      Result := True;
      Exit;
    end;
end;

function DesktopIconRequested: Boolean;
begin
  Result := HasSwitch('/DESKTOPICON');
end;

function RelaunchRequested: Boolean;
begin
  Result := HasSwitch('/RELAUNCH');
end;

function StopRuru(const Where: String; const Inside: Boolean): Boolean;
var
  Op, Code: Integer;
  Test: String;
begin
  { Stops Ruru.exe processes by where they run from, so only the copy being replaced is touched. }
  if Inside then Test := '-like' else Test := '-notlike';
  Result := Exec(ExpandConstant('{sys}\WindowsPowerShell\v1.0\powershell.exe'),
    '-NoProfile -NonInteractive -ExecutionPolicy Bypass -Command "Get-Process Ruru -ErrorAction SilentlyContinue ' +
    '| Where-Object { $_.Path -and $_.Path ' + Test + ' ''' + Where + '\*'' } | Stop-Process -Force"',
    '', SW_HIDE, ewWaitUntilTerminated, Code);
end;

{ ---------- removing an older MSI copy ---------- }

procedure RemoveOldMsiCopies;
var
  Index: Integer;
  Product: String;
  Rc: DWORD;
  Exit1: Integer;
  Dry: Boolean;
  Found: Integer;
begin
  Dry := HasSwitch('/DRYRUNMSI');
  Found := 0;
  Index := 0;
  while True do
  begin
    SetLength(Product, 39);
    Rc := MsiEnumRelatedProducts(UpgradeCode, 0, Index, Product);
    if Rc <> 0 then Break;   { 259 = no more products }
    Product := Copy(Product, 1, 38);
    Found := Found + 1;
    if Dry then
      Log('DRYRUNMSI: would remove the MSI copy of Ruru ' + Product)
    else
    begin
      Log('Removing the MSI copy of Ruru ' + Product);
      if not Plain then
      begin
        SubText.Caption := 'Removing the older copy first.';
        WizardForm.Refresh;
      end;
      StopRuru(ExpandConstant('{app}'), False);
      if Exec(ExpandConstant('{sys}\msiexec.exe'), '/x ' + Product + ' /qn /norestart', '', SW_HIDE,
              ewWaitUntilTerminated, Exit1) then
        Log('msiexec finished with ' + IntToStr(Exit1))
      else
        Log('msiexec could not start: ' + SysErrorMessage(DLLGetLastError));
    end;
    Index := Index + 1;
  end;
  if Found = 0 then Log('No MSI copy of Ruru found');
  if (not Plain) and (not Dry) then SubText.Caption := 'Copying files. This takes a few seconds.';
end;

function PrepareToInstall(var NeedsRestart: Boolean): String;
begin
  RemoveOldMsiCopies;
  Result := '';
end;

{ ---------- drawing ---------- }

procedure PutText(Canvas: TCanvas; const Face: String; Px: Integer; Color: TColor; const S: String; Cx, Top: Integer);
begin
  Canvas.Font.Name := Face;
  Canvas.Font.Height := -ScaleY(Px);
  Canvas.Font.Color := Color;
  Canvas.Brush.Style := bsClear;
  Canvas.TextOut(Cx - Canvas.TextWidth(S) div 2, Top, S);
end;

procedure DrawBar(Thousandths: Integer);
var
  C: TCanvas;
  W, H, F, R: Integer;
begin
  C := BarPic.Bitmap.Canvas;
  W := BarPic.Bitmap.Width;
  H := BarPic.Bitmap.Height;
  R := H;   { corner diameter: fully rounded ends }
  C.Brush.Style := bsSolid;
  C.Brush.Color := clNight;
  C.Pen.Style := psClear;
  C.Rectangle(0, 0, W + 1, H + 1);
  C.Brush.Color := clTrack;
  C.RoundRect(0, 0, W + 1, H + 1, R, R);
  F := W * Thousandths div 1000;
  if F > 0 then
  begin
    if F < H then F := H;
    C.Brush.Color := clEye;   { solid, no gradient }
    C.RoundRect(0, 0, F + 1, H + 1, R, R);
  end;
  BarPic.Invalidate;
end;

procedure DrawClose;
var
  C: TCanvas;
  W, H, Pad, Size: Integer;
begin
  C := ClosePic.Bitmap.Canvas;
  W := ClosePic.Bitmap.Width;
  H := ClosePic.Bitmap.Height;
  C.Brush.Style := bsSolid;
  C.Brush.Color := clNight;
  C.Pen.Style := psClear;
  C.Rectangle(0, 0, W + 1, H + 1);
  C.Pen.Style := psSolid;
  C.Pen.Width := 1;
  if CloseHot then C.Pen.Color := clInk else C.Pen.Color := clInk3;
  Size := ScaleX(10);
  Pad := (W - Size) div 2;
  C.MoveTo(Pad, Pad);
  C.LineTo(Pad + Size + 1, Pad + Size + 1);
  C.MoveTo(Pad + Size, Pad);
  C.LineTo(Pad - 1, Pad + Size + 1);
  ClosePic.Invalidate;
end;

procedure DrawOpen;
var
  C: TCanvas;
  W, H: Integer;
begin
  C := OpenPic.Bitmap.Canvas;
  W := OpenPic.Bitmap.Width;
  H := OpenPic.Bitmap.Height;
  C.Brush.Style := bsSolid;
  C.Brush.Color := clNight;
  C.Pen.Style := psClear;
  C.Rectangle(0, 0, W + 1, H + 1);
  if OpenHot then C.Brush.Color := clBtnHot else C.Brush.Color := clInk;
  C.RoundRect(0, 0, W + 1, H + 1, ScaleX(14), ScaleX(14));
  PutText(C, FontBold, 13, clNight, 'Open Ruru', W div 2, (H - ScaleY(13) * 12 div 10) div 2 - ScaleY(1));
  OpenPic.Invalidate;
end;

procedure StyleLabel(L: TLabel; const Face: String; Px: Integer; Color: TColor; Top, Left, Width, Height: Integer; Align: TAlignment);
begin
  L.Parent := WizardForm;
  L.AutoSize := False;
  L.Transparent := True;
  L.WordWrap := False;
  L.Font.Name := Face;
  L.Font.Height := -ScaleY(Px);
  L.Font.Color := Color;
  L.Alignment := Align;
  L.SetBounds(ScaleX(Left), ScaleY(Top), ScaleX(Width), ScaleY(Height));
end;

procedure ShowFinishedScreen;
begin
  Finished := True;
  TitleText.Caption := 'Ruru is installed';
  SubText.Caption := 'It''s in the Start menu. Put your headphones on to connect.';
  BarPic.Visible := False;
  NoteText.Visible := False;
  PercentText.Visible := False;
  OpenPic.Visible := True;
  DrawOpen;
end;

{ ---------- the glide ---------- }

function RealThousandths: Integer;
var
  Mx, Pos: Int64;
begin
  Result := 0;
  if WizardForm.CurPageID = wpFinished then
    Result := 1000
  else if WizardForm.CurPageID = wpInstalling then
  begin
    Mx := WizardForm.ProgressGauge.Max - WizardForm.ProgressGauge.Min;
    Pos := WizardForm.ProgressGauge.Position - WizardForm.ProgressGauge.Min;
    if Mx > 0 then Result := (Pos * 1000) div Mx;
  end;
  if Result > 1000 then Result := 1000;
end;

procedure Tick(Wnd, Msg, Id, Time: LongWord);
var
  P: TPt;
  Target, Step: Integer;
  Over: Boolean;
begin
  { Pointer over the close mark or the button? Tinted by hand, as the controls draw themselves. }
  if GetCursorPos(P) then
  begin
    ScreenToClient(WizardForm.Handle, P);
    Over := (P.X >= ClosePic.Left) and (P.X < ClosePic.Left + ClosePic.Width) and
            (P.Y >= ClosePic.Top) and (P.Y < ClosePic.Top + ClosePic.Height);
    if Over <> CloseHot then begin CloseHot := Over; DrawClose; end;
    if Finished then
    begin
      Over := (P.X >= OpenPic.Left) and (P.X < OpenPic.Left + OpenPic.Width) and
              (P.Y >= OpenPic.Top) and (P.Y < OpenPic.Top + OpenPic.Height);
      if Over <> OpenHot then begin OpenHot := Over; DrawOpen; end;
    end;
  end;

  { Drag by any empty spot: the window has no title bar, so start the system's move when the left button goes down. }
  Over := (GetAsyncKeyState($01) and $8000) <> 0;
  if Over and (not WasDown) and (P.X >= 0) and (P.Y >= 0) and (P.X < WizardForm.ClientWidth) and
     (P.Y < WizardForm.ClientHeight) and (not CloseHot) and (not (Finished and OpenHot)) then
  begin
    ReleaseCapture;
    SendMessage(WizardForm.Handle, WM_NCLBUTTONDOWN, HTCAPTION, 0);
  end;
  WasDown := Over;

  { The window has no choices to make, so the first page that asks to be confirmed (Ready) is confirmed here. }
  if (WizardForm.CurPageID = wpReady) and (not ReadyConfirmed) then
  begin
    ReadyConfirmed := True;
    PostMessage(WizardForm.NextButton.Handle, BM_CLICK, 0, 0);
  end;

  if Finished then Exit;
  Target := RealThousandths;
  if Shown < Target then
  begin
    { Ease toward the real progress: a share of the gap each frame, never a jump, never backwards. }
    Step := (Target - Shown) * 7 div 100;
    if Step < 1 then Step := 1;
    if Step > 12 then Step := 12;
    Shown := Shown + Step;
    if Shown > Target then Shown := Target;
    DrawBar(Shown);
    PercentText.Caption := IntToStr((Shown + 5) div 10) + '%';
  end;
  { Once the install is done and the bar has reached the end, show the finished screen. }
  if (WizardForm.CurPageID = wpFinished) and (Shown >= 1000) then
  begin
    DrawBar(1000);
    PercentText.Caption := '100%';
    ShowFinishedScreen;
  end;
end;

{ ---------- events ---------- }

procedure CloseClick(Sender: TObject);
begin
  { While files are being copied the close mark does nothing; on the finished screen it ends setup. }
  if Finished then WizardForm.NextButton.OnClick(WizardForm.NextButton);
end;

procedure OpenClick(Sender: TObject);
var
  Rc: Integer;
begin
  Exec(ExpandConstant('{app}\Ruru.exe'), '', ExpandConstant('{app}'), SW_SHOWNORMAL, ewNoWait, Rc);
  WizardForm.NextButton.OnClick(WizardForm.NextButton);
end;

procedure HideStandardPages;
begin
  { The wizard's own controls stay alive (setup drives them) but sit off-screen and out of the tab order. }
  WizardForm.OuterNotebook.Visible := False;
  WizardForm.Bevel.Visible := False;
  WizardForm.MainPanel.Visible := False;
  WizardForm.NextButton.SetBounds(-2000, -2000, 10, 10);
  WizardForm.BackButton.SetBounds(-2000, -2000, 10, 10);
  WizardForm.CancelButton.SetBounds(-2000, -2000, 10, 10);
  WizardForm.NextButton.TabStop := False;
  WizardForm.BackButton.TabStop := False;
  WizardForm.CancelButton.TabStop := False;
end;

function InitializeSetup: Boolean;
begin
  Plain := WizardSilent;
  Result := True;
end;

procedure InitializeWizard;
var
  Tmp: String;
begin
  if Plain then Exit;

  { Mona Sans for this window only (private to the setup process). Falls back to Segoe UI. }
  FontBold := 'Segoe UI Semibold';
  FontRegular := 'Segoe UI';
  ExtractTemporaryFile('monasans_regular.ttf');
  ExtractTemporaryFile('monasans_semibold.ttf');
  ExtractTemporaryFile('owl.bmp');
  Tmp := ExpandConstant('{tmp}');
  if AddFontResourceEx(Tmp + '\monasans_regular.ttf', FR_PRIVATE, 0) > 0 then FontRegular := 'Mona Sans';
  if AddFontResourceEx(Tmp + '\monasans_semibold.ttf', FR_PRIVATE, 0) > 0 then FontBold := 'Mona Sans SemiBold';

  WizardForm.BorderStyle := bsNone;
  WizardForm.ClientWidth := ScaleX(460);
  WizardForm.ClientHeight := ScaleY(320);
  WizardForm.Color := clNight;
  WizardForm.Position := poScreenCenter;
  HideStandardPages;
  SetWindowRgn(WizardForm.Handle, CreateRoundRectRgn(0, 0, WizardForm.Width + 1, WizardForm.Height + 1, ScaleX(16), ScaleX(16)), True);

  { The dark panel with its 1px edge. }
  Backdrop := TBitmapImage.Create(WizardForm);
  Backdrop.Parent := WizardForm;
  Backdrop.SetBounds(0, 0, WizardForm.ClientWidth, WizardForm.ClientHeight);
  Backdrop.Bitmap.Width := WizardForm.ClientWidth;
  Backdrop.Bitmap.Height := WizardForm.ClientHeight;
  with Backdrop.Bitmap.Canvas do
  begin
    Brush.Style := bsSolid;
    Brush.Color := clNight;
    Pen.Style := psClear;
    Rectangle(0, 0, WizardForm.ClientWidth + 1, WizardForm.ClientHeight + 1);
    Pen.Style := psSolid;
    Pen.Color := clEdge;
    Pen.Width := 1;
    RoundRect(0, 0, WizardForm.ClientWidth, WizardForm.ClientHeight, ScaleX(16), ScaleX(16));
  end;
  Backdrop.Enabled := False;   { clicks fall through to the window, which drags }

  OwlPic := TBitmapImage.Create(WizardForm);
  OwlPic.Parent := WizardForm;
  OwlPic.Stretch := True;
  OwlPic.SetBounds((WizardForm.ClientWidth - ScaleX(132)) div 2, ScaleY(32), ScaleX(132), ScaleY(132));
  OwlPic.Bitmap.LoadFromFile(Tmp + '\owl.bmp');
  OwlPic.Enabled := False;   { clicks fall through to the window, which drags }

  TitleText := TLabel.Create(WizardForm);
  StyleLabel(TitleText, FontBold, 17, clInk, 175, 0, 460, 24, taCenter);
  TitleText.Caption := 'Installing Ruru';

  SubText := TLabel.Create(WizardForm);
  StyleLabel(SubText, FontRegular, 13, clInk3, 202, 0, 460, 20, taCenter);
  SubText.Caption := 'Copying files. This takes a few seconds.';

  BarPic := TBitmapImage.Create(WizardForm);
  BarPic.Parent := WizardForm;
  BarPic.SetBounds(ScaleX(32), ScaleY(240), ScaleX(BarW), ScaleY(BarH));
  BarPic.Bitmap.Width := ScaleX(BarW);
  BarPic.Bitmap.Height := ScaleY(BarH);
  BarPic.Enabled := False;   { clicks fall through to the window, which drags }

  NoteText := TLabel.Create(WizardForm);
  StyleLabel(NoteText, FontRegular, 12, clInk3, 255, 32, 250, 18, taLeftJustify);
  NoteText.Caption := 'No admin password needed';

  PercentText := TLabel.Create(WizardForm);
  StyleLabel(PercentText, FontBold, 12, clInk, 255, 32 + BarW - 80, 80, 18, taRightJustify);
  PercentText.Caption := '0%';

  OpenPic := TBitmapImage.Create(WizardForm);
  OpenPic.Parent := WizardForm;
  OpenPic.SetBounds((WizardForm.ClientWidth - ScaleX(BtnW)) div 2, ScaleY(240), ScaleX(BtnW), ScaleY(BtnH));
  OpenPic.Bitmap.Width := ScaleX(BtnW);
  OpenPic.Bitmap.Height := ScaleY(BtnH);
  OpenPic.Cursor := crHand;
  OpenPic.OnClick := @OpenClick;
  OpenPic.Visible := False;

  ClosePic := TBitmapImage.Create(WizardForm);
  ClosePic.Parent := WizardForm;
  ClosePic.SetBounds(WizardForm.ClientWidth - ScaleX(36), ScaleY(8), ScaleX(24), ScaleX(24));
  ClosePic.Bitmap.Width := ScaleX(24);
  ClosePic.Bitmap.Height := ScaleX(24);
  ClosePic.Cursor := crHand;
  ClosePic.OnClick := @CloseClick;

  DrawBar(0);
  DrawClose;

  TimerId := SetTimer(0, 0, 16, CreateCallback(@Tick));
end;

procedure CurPageChanged(CurPageID: Integer);
begin
  if Plain then Exit;
  HideStandardPages;
end;

{ ---------- uninstall ---------- }

function InitializeUninstall: Boolean;
begin
  { Close the running copy of this install (and only this one) so its files can be removed. }
  StopRuru(ExpandConstant('{app}'), True);
  Result := True;
end;
